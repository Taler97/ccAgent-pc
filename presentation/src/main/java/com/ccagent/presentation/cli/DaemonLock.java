package com.ccagent.presentation.cli;

import com.ccagent.infrastructure.constant.Constants;
import com.ccagent.infrastructure.util.FileUtil;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Logger;

/**
 * 引擎单实例锁
 * <p>基于 {@code .codepal/daemon.lock} 文件锁（{@link FileChannel#tryLock()}）保证同一时刻
 * 只有一个引擎进程（默认 CLI / {@code --background} / 旧入口共用）。
 * 获取失败意味着引擎已在运行，启动方应拒绝启动（方案 A）。</p>
 *
 * <p>同时记录 {@code .codepal/daemon.pid}，供孤儿检测使用。</p>
 */
public class DaemonLock implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(DaemonLock.class.getName());

    private static final Path LOCK_FILE = Paths.get(Constants.BASE_DIR, "daemon.lock");
    private static final Path PID_FILE = Paths.get(Constants.BASE_DIR, "daemon.pid");

    private RandomAccessFile raf;
    private FileChannel channel;
    private FileLock lock;
    private boolean ownsLock = false;

    private DaemonLock() {}

    /**
     * 尝试获取单实例锁。
     *
     * @return 锁句柄；若引擎已在运行返回 {@code null}（调用方应提示并退出）
     */
    public static DaemonLock tryAcquire() {
        DaemonLock holder = new DaemonLock();
        try {
            FileUtil.createDirectories(LOCK_FILE.getParent());

            holder.raf = new RandomAccessFile(LOCK_FILE.toFile(), "rw");
            holder.channel = holder.raf.getChannel();
            holder.lock = holder.channel.tryLock();
            if (holder.lock == null) {
                holder.release();
                return null;
            }

            holder.ownsLock = true;
            // 记录 PID（截断写入）
            FileUtil.writeString(PID_FILE, String.valueOf(ProcessHandle.current().pid()));
            LOG.info("已获取引擎单实例锁: " + LOCK_FILE);
            return holder;
        } catch (IOException | OverlappingFileLockException e) {
            holder.release();
            return null;
        }
    }

    /**
     * 释放锁并删除 PID 记录（仅本实例持有锁时）。
     */
    @Override
    public void close() {
        release();
    }

    private void release() {
        try {
            if (lock != null && lock.isValid()) {
                lock.release();
            }
        } catch (IOException e) {
            LOG.warning("释放单实例锁失败: " + e.getMessage());
        }
        try {
            if (channel != null) channel.close();
        } catch (IOException e) {
            LOG.warning("关闭锁通道失败: " + e.getMessage());
        }
        try {
            if (raf != null) raf.close();
        } catch (IOException e) {
            LOG.warning("关闭锁文件失败: " + e.getMessage());
        }
        if (ownsLock) {
            FileUtil.delete(PID_FILE);
        }
        lock = null;
        channel = null;
        raf = null;
        ownsLock = false;
    }
}
