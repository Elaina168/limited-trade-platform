package com.elaina.limitedtrade.agent;

import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import org.reactivestreams.Subscription;

// 一个流式请求的生命周期；取消和数据库提交通过同一锁确定先后顺序。
public class AgentStream {
    private final Consumer<Event> listener;
    private volatile boolean cancelled;
    private Subscription subscription;
    private Thread worker;

    public AgentStream(Consumer<Event> listener) { this.listener = listener; }

    public void emit(String type, int round, String text) {
        check();
        listener.accept(new Event(type, round, text));
    }

    public void check() {
        if (cancelled || Thread.currentThread().isInterrupted()) throw new CancellationException("流式请求已取消");
    }

    public boolean cancelled() { return cancelled; }

    public synchronized void subscribe(Subscription value) {
        subscription = value;
        if (cancelled) value.cancel();
    }

    public synchronized void bindWorker() { check(); worker = Thread.currentThread(); }

    public synchronized void unbindWorker() {
        if (worker == Thread.currentThread()) {
            worker = null;
            if (cancelled) Thread.interrupted();
        }
    }

    public synchronized void cancel() {
        cancelled = true;
        if (subscription != null) subscription.cancel();
        if (worker != null) worker.interrupt();
    }

    public synchronized void commit(Runnable action) { check(); action.run(); }

    public record Event(String type, int round, String text) { }
}
