package com.ccagent.api.port;

import com.ccagent.api.model.response.StreamChunk;

import java.util.concurrent.atomic.AtomicBoolean;

public class SafeStreamObserver implements StreamObserver<StreamChunk> {

    private final StreamObserver<StreamChunk> delegate;
    private final AtomicBoolean completed = new AtomicBoolean(false);

    public SafeStreamObserver(StreamObserver<StreamChunk> delegate) {
        this.delegate = delegate;
    }

    @Override
    public void onNext(StreamChunk chunk) {
        if (completed.get()) return;
        delegate.onNext(chunk);
    }

    @Override
    public void onError(Throwable error) {
        if (completed.getAndSet(true)) return;
        delegate.onError(error);
    }

    @Override
    public void onComplete() {
        if (completed.getAndSet(true)) return;
        delegate.onComplete();
    }
}
