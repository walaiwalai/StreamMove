package com.sh.engine.task;

import com.sh.config.utils.EnvUtil;
import com.sh.config.utils.ExecutorPoolUtil;
import org.redisson.api.RExecutorFuture;
import org.redisson.api.RScheduledExecutorService;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;

/** 只负责命名路由和等待；执行与结果传递交给 Redisson。 */
@Component
public class TaskClient {
    private static final long QUEUE_TTL_SECONDS = 300;
    private static final long WAIT_TIMEOUT_SECONDS = 900;
    private final boolean taskMode;
    private final Map<TaskType, RScheduledExecutorService> executors = new EnumMap<>(TaskType.class);

    public TaskClient(RedissonClient computeRedissonClient, @Value("${system.env.flag}") String env) {
        if (env == null || env.trim().isEmpty()) {
            throw new IllegalArgumentException("system.env.flag is required for task isolation");
        }
        this.taskMode = EnvUtil.isTaskMode();
        for (TaskType type : TaskType.values()) {
            executors.put(type, computeRedissonClient.getExecutorService(
                    "sm:" + env + ":compute:" + type.name().toLowerCase(Locale.ROOT)));
        }
    }

    RScheduledExecutorService executor(TaskType type) {
        return Objects.requireNonNull(executors.get(Objects.requireNonNull(type, "type")), "executor");
    }

    boolean isTaskMode() {
        return taskMode;
    }

    public <R> RExecutorFuture<R> submit(TaskType type, Callable<R> task) {
        if (taskMode) {
            throw new IllegalStateException("Only driver mode submits compute tasks");
        }
        if (!(task instanceof Serializable)) {
            throw new IllegalArgumentException("Task must be Serializable");
        }
        return executor(type).submitAsync(task, QUEUE_TTL_SECONDS, TimeUnit.SECONDS);
    }

    public <R> CompletableFuture<R> resultAsync(RExecutorFuture<R> task, Duration waitTimeout) {
        Objects.requireNonNull(task, "task");
        long millis = Objects.requireNonNull(waitTimeout, "waitTimeout").toMillis();
        if (millis <= 0 || millis >= TimeUnit.HOURS.toMillis(1)) {
            throw new IllegalArgumentException("Wait timeout must be positive and below one hour");
        }
        CompletableFuture<R> waiting = new CompletableFuture<>();
        ScheduledFuture<?> timer = ExecutorPoolUtil.getFlushPool().schedule(
                () -> waiting.completeExceptionally(new TimeoutException("Compute task wait timed out")),
                millis, TimeUnit.MILLISECONDS);
        waiting.whenComplete((value, error) -> timer.cancel(false));
        task.whenComplete((value, error) -> {
            if (error == null) {
                waiting.complete(value);
            } else {
                waiting.completeExceptionally(error);
            }
        });
        return waiting;
    }

    public <R> CompletableFuture<R> resultAsync(RExecutorFuture<R> task) {
        return resultAsync(task, Duration.ofSeconds(WAIT_TIMEOUT_SECONDS));
    }

    public <R> R result(RExecutorFuture<R> task, Duration waitTimeout)
            throws InterruptedException, ExecutionException, TimeoutException {
        CompletableFuture<R> waiting = resultAsync(task, waitTimeout);
        try {
            return waiting.get();
        } catch (InterruptedException error) {
            waiting.cancel(false);
            throw error;
        } catch (ExecutionException error) {
            if (error.getCause() instanceof TimeoutException) {
                throw (TimeoutException) error.getCause();
            }
            throw error;
        }
    }

    public <R> R result(RExecutorFuture<R> task)
            throws InterruptedException, ExecutionException, TimeoutException {
        return result(task, Duration.ofSeconds(WAIT_TIMEOUT_SECONDS));
    }
}
