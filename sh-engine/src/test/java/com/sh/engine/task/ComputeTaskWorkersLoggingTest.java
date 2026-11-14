package com.sh.engine.task;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RScheduledExecutorService;
import org.redisson.api.WorkerOptions;
import org.redisson.api.executor.TaskFailureListener;
import org.redisson.api.executor.TaskListener;
import org.redisson.api.executor.TaskStartedListener;
import org.redisson.api.executor.TaskSuccessListener;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class ComputeTaskWorkersLoggingTest {
    @Test
    public void onlyCompletionAndFailureAreLoggedWithElapsedTime() throws InterruptedException {
        TaskClient client = mock(TaskClient.class);
        when(client.isTaskMode()).thenReturn(true);
        RScheduledExecutorService executor = mock(RScheduledExecutorService.class);
        when(client.executor(any(TaskType.class))).thenReturn(executor);
        ComputeTaskWorkers workers = new ComputeTaskWorkers(client, mock(ConfigurableListableBeanFactory.class));
        Logger logger = (Logger) LoggerFactory.getLogger(ComputeTaskWorkers.class);
        Level previous = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.setLevel(Level.INFO);
        logger.addAppender(appender);
        try {
            workers.afterSingletonsInstantiated();
            ArgumentCaptor<WorkerOptions> options = ArgumentCaptor.forClass(WorkerOptions.class);
            verify(executor, times(TaskType.values().length)).registerWorkers(options.capture());
            TaskListener listener = options.getAllValues().get(0).getListeners().get(0);

            ((TaskStartedListener) listener).onStarted("success-id");
            assertTrue("Starting must only record the clock", appender.list.isEmpty());
            Thread.sleep(15);
            ((TaskSuccessListener) listener).onSucceeded("success-id", "result");
            assertEquals(1, appender.list.size());
            assertEquals(Level.INFO, appender.list.get(0).getLevel());
            assertTrue(appender.list.get(0).getFormattedMessage().contains("Compute task succeeded"));
            assertTrue(appender.list.get(0).getFormattedMessage().contains("taskId: success-id"));
            assertTrue(appender.list.get(0).getFormattedMessage().matches(".*executionMillis: [1-9]\\d*.*"));

            ((TaskStartedListener) listener).onStarted("failure-id");
            assertEquals("Starting another task must also stay silent", 1, appender.list.size());
            Thread.sleep(15);
            ((TaskFailureListener) listener).onFailed("failure-id", new IllegalStateException("test failure"));
            assertEquals(2, appender.list.size());
            ILoggingEvent failure = appender.list.get(1);
            assertEquals(Level.ERROR, failure.getLevel());
            assertTrue(failure.getFormattedMessage().contains("Compute task failed"));
            assertTrue(failure.getFormattedMessage().contains("taskId: failure-id"));
            assertTrue(failure.getFormattedMessage().matches(".*executionMillis: [1-9]\\d*.*"));
            assertTrue(failure.getFormattedMessage().contains("errorType: " + IllegalStateException.class.getName()));
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previous);
            appender.stop();
            workers.destroy();
        }
    }
}
