package com.pivotos.starter.cloud.sc.fixture;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 会数数的 EchoFacade 替身。
 *
 * <p>「熔断打开后请求<b>没有</b>打到下游」这件事只能靠这种计数器证明：
 * 光看抛出的异常，无法区分「被熔断拦下」和「下游又失败了一次」。
 */
public class RecordingEchoFacade implements EchoFacade {

    private final AtomicInteger calls = new AtomicInteger();
    private volatile boolean failing = true;
    private volatile String reply = "local";

    @Override
    public String echo(String value) {
        calls.incrementAndGet();
        if (failing) {
            throw new IllegalStateException("下游不可用：" + value);
        }
        return reply + ":" + value;
    }

    @Override
    public void ping() {
        calls.incrementAndGet();
        if (failing) {
            throw new IllegalStateException("下游不可用");
        }
    }

    public int calls() {
        return calls.get();
    }

    public void healTo(String value) {
        this.failing = false;
        this.reply = value;
    }

    public void reset() {
        calls.set(0);
    }
}
