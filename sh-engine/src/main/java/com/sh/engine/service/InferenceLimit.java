package com.sh.engine.service;

import java.util.concurrent.Callable;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** Limits concurrent HTTP inference calls without another executor or remote queue. */
public enum InferenceLimit {
    OCR(2, 60),
    YOLO(1, 60),
    ASR(1, 240),
    LLM(1, 600);

    private static final long CAPACITY_WAIT_SECONDS = 300;
    private final Semaphore permits;
    private final long timeoutSeconds;

    InferenceLimit(int concurrency, long timeoutSeconds) {
        this.permits = new Semaphore(concurrency, true);
        this.timeoutSeconds = timeoutSeconds;
    }

    public long timeoutSeconds() {
        return timeoutSeconds;
    }

    public <T> T call(Callable<T> request) {
        boolean acquired = false;
        try {
            if (!permits.tryAcquire(CAPACITY_WAIT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for " + name() + " capacity");
            }
            acquired = true;
            return request.call();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while calling " + name(), error);
        } catch (RuntimeException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException(name() + " HTTP request failed", error);
        } finally {
            if (acquired) {
                permits.release();
            }
        }
    }
}
