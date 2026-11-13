package com.sh.engine.task;

import cn.hutool.extra.spring.SpringUtil;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.junit.Test;
import org.redisson.api.RExecutorFuture;
import org.redisson.api.RScheduledExecutorService;
import org.redisson.api.RedissonClient;
import org.redisson.executor.RedissonExecutorFuture;

import java.io.Serializable;
import java.time.Duration;
import java.util.concurrent.*;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class TaskClientTest {
    private TaskClient client(String mode) {
        RedissonClient redis = mock(RedissonClient.class);
        when(redis.getExecutorService(anyString())).thenReturn(mock(RScheduledExecutorService.class));
        Environment environment = mock(Environment.class);
        when(environment.getProperty("streammove.mode", String.class)).thenReturn(mode);
        ApplicationContext previous = SpringUtil.getApplicationContext();
        ApplicationContext context = mock(ApplicationContext.class);
        when(context.getEnvironment()).thenReturn(environment);
        try {
            new SpringUtil().setApplicationContext(context);
            return new TaskClient(redis, "test");
        } finally {
            new SpringUtil().setApplicationContext(previous);
        }
    }

    @Test
    public void timeoutOnlyEndsItsOwnWaitAndResultCanStillArrive() throws Exception {
        TaskClient client = client("driver");
        CompletableFuture<String> original = new CompletableFuture<>();
        RExecutorFuture<String> task = new RedissonExecutorFuture<>(original, "task-id");
        CompletableFuture<String> first = client.resultAsync(task, Duration.ofMillis(20));
        CompletableFuture<String> second = client.resultAsync(task, Duration.ofSeconds(2));
        try { first.get(2, TimeUnit.SECONDS); fail("timeout expected"); }
        catch (ExecutionException expected) { assertTrue(expected.getCause() instanceof TimeoutException); }
        assertFalse(original.isDone());
        original.complete("result");
        assertEquals("result", second.get(2, TimeUnit.SECONDS));
        assertEquals("result", client.result(task));
    }

    @Test
    public void cancellingWaitDoesNotCancelUnderlyingTask() {
        TaskClient client = client("driver");
        CompletableFuture<String> original = new CompletableFuture<>();
        RExecutorFuture<String> task = new RedissonExecutorFuture<>(original, "task-id");
        client.resultAsync(task).cancel(false);
        assertFalse(original.isCancelled());
    }

    @Test
    public void synchronousTimeoutIsReportedDirectly() throws Exception {
        try {
            client("driver").result(new RedissonExecutorFuture<>(new CompletableFuture<>(), "id"), Duration.ofMillis(10));
            fail("timeout expected");
        } catch (TimeoutException expected) { }
    }

    @Test
    public void taskModeCannotRecursivelySubmit() {
        try { client("task").submit(TaskType.OCR, new ValueTask()); fail("submission allowed"); }
        catch (IllegalStateException expected) { }
    }

    @Test
    public void invalidWaitDoesNotStartWaiting() {
        try {
            client("driver").resultAsync(new RedissonExecutorFuture<>(new CompletableFuture<>(), "id"), Duration.ZERO);
            fail("invalid timeout accepted");
        } catch (IllegalArgumentException expected) { }
    }

    public static class ValueTask implements Callable<String>, Serializable {
        @Override public String call() { return "value"; }
    }
}
