package com.pivotos.monitor.service.health;

/** 探活耗时：nanos → ms（各检查项共用，避免每处重复写一套）。 */
final class Elapsed {

    private Elapsed() {
    }

    static long since(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
