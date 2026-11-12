package com.sh.engine.task;

public enum TaskType {
    OCR(2, 60),
    YOLO(1, 60),
    ASR(1, 240),
    LLM(1, 600);

    private final int threads;
    private final long executionTimeoutSeconds;

    TaskType(int threads, long executionTimeoutSeconds) {
        this.threads = threads;
        this.executionTimeoutSeconds = executionTimeoutSeconds;
    }

    public int getThreads() {
        return threads;
    }

    public long getExecutionTimeoutSeconds() {
        return executionTimeoutSeconds;
    }
}
