package com.ccagent.api.port;

/**
 * 流式结果观察者
 * <p>用于异步方法的结果回调，供编排器/适配器向调用方推送数据块、状态和错误。</p>
 *
 * @param <T> 数据块类型
 */
public interface StreamObserver<T> {

    /**
     * 推送一个数据块
     * @param chunk 数据块
     */
    void onNext(T chunk);

    /**
     * 推送错误
     * @param error 异常
     */
    void onError(Throwable error);

    /**
     * 推送完成信号
     */
    void onComplete();
}
