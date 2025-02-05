package com.sh.config.utils;

import com.google.common.util.concurrent.ThreadFactoryBuilder;

import java.util.concurrent.*;

/**
 * 线程池工具类
 *
 * @Author caiwen
 * @Date 2024 09 28 10 26
 **/
public class ExecutorPoolUtil {
    private static final int CORE_COUNT = Runtime.getRuntime().availableProcessors();
    private static final ExecutorService downloadPool = new ThreadPoolExecutor(
            CORE_COUNT * 2,
            CORE_COUNT * 2,
            600,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(20480),
            new ThreadFactoryBuilder().setNameFormat("seg-download-%d").build(),
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    private static final ExecutorService uploadPool = new ThreadPoolExecutor(
            CORE_COUNT * 2,
            CORE_COUNT * 2,
            600,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(40960),
            new ThreadFactoryBuilder().setNameFormat("upload-thread-%d").build(),
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    private static final ExecutorService dynamicPool = new ThreadPoolExecutor(
            0,
            Integer.MAX_VALUE,
            30,
            TimeUnit.SECONDS,
            new SynchronousQueue<Runnable>(),
            new ThreadFactoryBuilder().setNameFormat("dynamic-thread-%d").build(),
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    public static ExecutorService getDownloadPool() {
        return downloadPool;
    }

    public static ExecutorService getUploadPool() {
        return uploadPool;
    }

    public static ExecutorService getDynamicPool() {
        return dynamicPool;
    }
}
