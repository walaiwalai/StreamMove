package com.sh.engine.task;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RScheduledExecutorService;
import org.redisson.api.WorkerOptions;
import org.redisson.api.executor.TaskFailureListener;
import org.redisson.api.executor.TaskStartedListener;
import org.redisson.api.executor.TaskSuccessListener;
import org.redisson.executor.SpringTasksInjector;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.*;

/** task 启动时一次注册 SDK Worker；没有应用层轮询和结果回写任务。 */
@Component
@Slf4j
public class ComputeTaskWorkers implements SmartInitializingSingleton, DisposableBean {
    private final TaskClient client;
    private final ConfigurableListableBeanFactory beanFactory;
    private final Map<RScheduledExecutorService, ExecutorService> pools = new LinkedHashMap<>();

    public ComputeTaskWorkers(TaskClient client, ConfigurableListableBeanFactory beanFactory) {
        this.client = client;
        this.beanFactory = beanFactory;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (!client.isTaskMode()) {
            return;
        }
        for (TaskType type : TaskType.values()) {
            int threads = type.getThreads();
            ExecutorService pool = new ThreadPoolExecutor(threads, threads, 0, TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(threads),
                    new ThreadFactoryBuilder().setNameFormat("compute-" + type.name().toLowerCase(java.util.Locale.ROOT) + "-%d").build(),
                    new ThreadPoolExecutor.AbortPolicy());
            RScheduledExecutorService executor = client.executor(type);
            pools.put(executor, pool);
            executor.registerWorkers(WorkerOptions.defaults().workers(threads).executorService(pool)
                    .tasksInjector(new SpringTasksInjector(beanFactory))
                    .addListener(new TaskLogListener(type))
                    .taskTimeout(type.getExecutionTimeoutSeconds(), TimeUnit.SECONDS));
        }
    }

    /** 开始回调只记录计时；成功、失败时输出结果与耗时。 */
    private static final class TaskLogListener implements TaskStartedListener, TaskSuccessListener, TaskFailureListener {
        private final TaskType type;
        private final ThreadLocal<Long> started = new ThreadLocal<>();

        private TaskLogListener(TaskType type) {
            this.type = type;
        }

        @Override
        public void onStarted(String taskId) {
            started.set(System.nanoTime());
        }

        @Override
        public <T> void onSucceeded(String taskId, T result) {
            log.info("Compute task succeeded, type: {}, taskId: {}, executionMillis: {}",
                    type, taskId, elapsedMillis());
        }

        @Override
        public void onFailed(String taskId, Throwable error) {
            log.error("Compute task failed, type: {}, taskId: {}, executionMillis: {}, errorType: {}",
                    type, taskId, elapsedMillis(), error == null ? "unknown" : error.getClass().getName());
        }

        private long elapsedMillis() {
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started.get());
            started.remove();
            return elapsed;
        }
    }

    @Override
    public void destroy() {
        for (Map.Entry<RScheduledExecutorService, ExecutorService> entry : pools.entrySet()) {
            try {
                entry.getKey().deregisterWorkers();
            } catch (RuntimeException error) {
                log.warn("Compute Worker deregistration failed, errorType: {}", error.getClass().getName());
            } finally {
                entry.getValue().shutdownNow();
            }
        }
        pools.clear();
    }
}
