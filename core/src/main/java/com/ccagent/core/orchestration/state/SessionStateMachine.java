package com.ccagent.core.orchestration.state;

import com.ccagent.core.event.ExecutionCompletedEvent;
import com.ccagent.core.event.ExecutionFailedEvent;
import com.ccagent.core.event.SessionStateChangedEvent;
import com.ccagent.kernel.event.EventBus;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

/**
 * 会话状态机（事件驱动）
 * <p>会话并发模型的三层规约之一：<b>锁只护状态转移（≤1ms），串行性由状态机裁决保证</b>。
 * 替代原先的 session 长锁——长任务（LLM/规划/审批等待/DAG 执行）全部锁外执行，
 * 会话忙判定通过 inFlight 标志原子完成（D1：忙则拒绝，错误码 SESSION_BUSY）。</p>
 *
 * <h3>事件驱动</h3>
 * <ul>
 *   <li>每次状态转移统一经 {@link #applyState} 原子执行并发布 {@link SessionStateChangedEvent}（审计/观测）；</li>
 *   <li>订阅 {@link ExecutionCompletedEvent}/{@link ExecutionFailedEvent}，执行结束自动复位会话状态（事件驱动清理）。</li>
 * </ul>
 */
public class SessionStateMachine {

    private static final Logger LOG = Logger.getLogger(SessionStateMachine.class.getName());

    public static final String IDLE = "IDLE";
    public static final String PLANNING = "PLANNING";
    public static final String WAIT_APPROVAL = "WAIT_APPROVAL";
    public static final String EXECUTING = "EXECUTING";
    public static final String ERROR = "ERROR";

    /** 合法转移表：from → 允许的 to 集合 */
    private static final Map<String, Set<String>> TRANSITIONS = Map.of(
            IDLE, Set.of(PLANNING, ERROR),
            PLANNING, Set.of(WAIT_APPROVAL, EXECUTING, IDLE, ERROR),
            WAIT_APPROVAL, Set.of(EXECUTING, IDLE, ERROR),
            EXECUTING, Set.of(IDLE, ERROR),
            ERROR, Set.of(IDLE)
    );

    /** sessionId → 会话状态 */
    private final ConcurrentHashMap<String, StateHolder> states = new ConcurrentHashMap<>();
    /** seqId → sessionId（EXECUTE_PLAN/CANCEL 只带 seqId，需反查会话） */
    private final ConcurrentHashMap<String, String> seqToSession = new ConcurrentHashMap<>();

    private final EventBus eventBus;

    private static final class StateHolder {
        final AtomicReference<String> agentState = new AtomicReference<>(IDLE);
        final AtomicBoolean inFlight = new AtomicBoolean(false);
        final AtomicReference<String> currentSeqId = new AtomicReference<>("");
        final AtomicReference<CompletableFuture<Boolean>> approvalFuture = new AtomicReference<>();
        final AtomicBoolean cancelFlag = new AtomicBoolean(false);
    }

    public SessionStateMachine(EventBus eventBus) {
        this.eventBus = eventBus;
        // 事件驱动清理：执行完成/失败自动复位会话状态（幂等）
        if (eventBus != null) {
            eventBus.subscribe(ExecutionCompletedEvent.class, e -> finishBySeq(e.getSeqId(), IDLE));
            eventBus.subscribe(ExecutionFailedEvent.class, e -> finishBySeq(e.getSeqId(), ERROR));
        }
    }

    // ===== 准入与生命周期 =====

    /**
     * 尝试开始执行：会话空闲 → 置 PLANNING + inFlight，返回 true；忙 → false。
     * <p>唯一入口，原子完成"忙判定 + 状态写入"，锁内极短。</p>
     */
    public boolean tryBegin(String sessionId, String seqId) {
        StateHolder h = states.computeIfAbsent(sessionId, k -> new StateHolder());
        if (!h.inFlight.compareAndSet(false, true)) {
            return false;
        }
        h.currentSeqId.set(seqId);
        h.cancelFlag.set(false);
        h.approvalFuture.set(null);
        seqToSession.put(seqId, sessionId);
        applyState(sessionId, IDLE, PLANNING, seqId);
        return true;
    }

    /**
     * 当前执行使用的取消标志（与 Dispatcher 共享，取消时置位）。
     * 仅 tryBegin 成功后可用。
     */
    public AtomicBoolean getCancelFlag(String sessionId) {
        StateHolder h = states.get(sessionId);
        return h != null ? h.cancelFlag : null;
    }

    public boolean isCancelled(String sessionId) {
        AtomicBoolean flag = getCancelFlag(sessionId);
        return flag != null && flag.get();
    }

    /**
     * 注册审批等待（PLANNING → WAIT_APPROVAL），执行线程随后锁外挂起。
     */
    public void markApproval(String sessionId, String seqId, CompletableFuture<Boolean> future) {
        StateHolder h = states.get(sessionId);
        if (h == null) return;
        h.approvalFuture.set(future);
        applyState(sessionId, PLANNING, WAIT_APPROVAL, seqId);
    }

    /**
     * 审批确认（EXECUTE_PLAN 到达）：唤醒等待的 future；确认时 WAIT_APPROVAL → EXECUTING。
     *
     * @return true 若该 seqId 存在对应审批等待
     */
    public boolean confirmApproval(String seqId, boolean confirmed) {
        String sessionId = seqToSession.get(seqId);
        if (sessionId == null) return false;
        StateHolder h = states.get(sessionId);
        if (h == null) return false;
        CompletableFuture<Boolean> future = h.approvalFuture.get();
        if (future != null) {
            future.complete(confirmed);
        }
        if (confirmed) {
            applyState(sessionId, WAIT_APPROVAL, EXECUTING, seqId);
        }
        return true;
    }

    /**
     * 清理审批等待（审批完成/超时后由执行线程调用）。
     */
    public void clearApproval(String sessionId, String seqId) {
        StateHolder h = states.get(sessionId);
        if (h != null) {
            h.approvalFuture.set(null);
        }
    }

    /**
     * 取消执行（CANCEL_EXECUTION/CANCEL_PLAN）：置取消标志并唤醒审批等待（false）。
     *
     * @return true 若该 seqId 存在对应执行
     */
    public boolean requestCancel(String seqId) {
        String sessionId = seqToSession.get(seqId);
        if (sessionId == null) return false;
        StateHolder h = states.get(sessionId);
        if (h == null) return false;
        h.cancelFlag.set(true);
        CompletableFuture<Boolean> future = h.approvalFuture.get();
        if (future != null && !future.isDone()) {
            future.complete(false);
        }
        return true;
    }

    /**
     * 标记失败（* → ERROR），随后由 finish 复位到 IDLE。
     */
    public void markError(String sessionId, String seqId) {
        StateHolder h = states.get(sessionId);
        if (h == null) return;
        String from = h.agentState.get();
        applyState(sessionId, from, ERROR, seqId);
    }

    /**
     * 执行结束复位：inFlight=false，状态 → IDLE，清理 seq 映射。
     * 幂等（事件驱动的 finishBySeq 与 execute 的 finally 都可能触发）。
     */
    public void finish(String sessionId, String seqId) {
        StateHolder h = states.get(sessionId);
        if (h == null) return;
        h.inFlight.set(false);
        h.approvalFuture.set(null);
        if (seqId != null && seqId.equals(h.currentSeqId.get())) {
            seqToSession.remove(seqId);
        }
        String from = h.agentState.get();
        applyState(sessionId, from, IDLE, seqId);
    }

    /** 由执行生命周期事件驱动：执行完成/失败 → 复位会话状态 */
    private void finishBySeq(String seqId, String targetState) {
        String sessionId = seqToSession.get(seqId);
        if (sessionId == null) return;
        StateHolder h = states.get(sessionId);
        if (h == null) return;
        h.inFlight.set(false);
        h.approvalFuture.set(null);
        seqToSession.remove(seqId);
        applyState(sessionId, h.agentState.get(), targetState, seqId);
        applyState(sessionId, targetState, IDLE, seqId);
    }

    public String getAgentState(String sessionId) {
        StateHolder h = states.get(sessionId);
        return h != null ? h.agentState.get() : IDLE;
    }

    public boolean isBusy(String sessionId) {
        StateHolder h = states.get(sessionId);
        return h != null && h.inFlight.get();
    }

    // ===== 内部 =====

    /**
     * 统一状态转移：校验合法性 + CAS 原子更新 + 发布事件。幂等（同状态 CAS 失败返回 false）。
     */
    private void applyState(String sessionId, String from, String to, String seqId) {
        StateHolder h = states.get(sessionId);
        if (h == null) return;
        // 同状态转移视为幂等成功
        if (from.equals(to)) {
            return;
        }
        if (!isValidTransition(from, to)) {
            LOG.warning("非法会话状态转移: sessionId=" + sessionId
                    + " " + from + " → " + to + "（忽略）");
            return;
        }
        if (!h.agentState.compareAndSet(from, to)) {
            // 并发下状态已被其他线程推进，忽略本次
            return;
        }
        if (eventBus != null) {
            eventBus.publish(new SessionStateChangedEvent(sessionId, from, to, seqId));
        }
    }

    private static boolean isValidTransition(String from, String to) {
        Set<String> allowed = TRANSITIONS.get(from);
        return allowed != null && allowed.contains(to);
    }
}
