package com.sh.config.utils;

import com.google.common.util.concurrent.ThreadFactoryBuilder;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 线程池工具类
 * @Author caiwen
 * @Date 2024 09 28 10 26
 **/
public class ExecutorPoolUtil {
    private static ExecutorService downloadPool = new ThreadPoolExecutor(
            8,
            8,
            600,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(20480),
            new ThreadFactoryBuilder().setNameFormat("seg-download-%d").build(),
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    public static ExecutorService getDownloadPool() {
        return downloadPool;
    }
}
