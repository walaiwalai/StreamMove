package com.sh.config.utils;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import lombok.Getter;

import java.util.concurrent.*;

/**
 * 线程池工具类
 *
 * @Author caiwen
 * @Date 2024 09 28 10 26
 **/
public class ExecutorPoolUtil {
    private static final int CORE_COUNT = Runtime.getRuntime().availableProcessors();

    @Getter
    private static final ExecutorService uploadPool = new ThreadPoolExecutor(
            CORE_COUNT * 2,
            CORE_COUNT * 2,
            600,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(40960),
            new ThreadFactoryBuilder().setNameFormat("upload-thread-%d").build(),
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    @Getter
    private static final ScheduledExecutorService flushPool = Executors.newScheduledThreadPool(CORE_COUNT);
}
