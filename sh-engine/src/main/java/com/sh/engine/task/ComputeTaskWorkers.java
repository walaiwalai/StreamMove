package com.sh.engine.task;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RScheduledExecutorService;
import org.redisson.api.WorkerOptions;
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
                    .taskTimeout(type.getExecutionTimeoutSeconds(), TimeUnit.SECONDS));
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
