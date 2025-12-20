package com.sh.engine.model.ffmpeg;

import com.sh.config.exception.ErrorEnum;
import com.sh.config.exception.StreamerRecordException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.exec.*;
import org.apache.commons.lang3.SystemUtils;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 命令行基础类
 *
 * @Author caiwen
 * @Date 2025 02 09 11 43
 **/
@Slf4j
public abstract class AbstractCmd {
    private volatile ExecuteWatchdog watchdog;
    private final AtomicBoolean isTimeout = new AtomicBoolean(false);
    /**
     * 默认值，表示未执行或执行失败
     */
    private int exitCode = -1;

    private long startTime;


    protected String command;

    protected AbstractCmd(String command) {
        this.command = command;
    }

    public void execute(long timeoutSeconds) {
        CommandLine cmdLine;
        if (SystemUtils.IS_OS_WINDOWS) {
            cmdLine = CommandLine.parse("cmd.exe");
            cmdLine.addArgument("/c");
        } else {
            cmdLine = CommandLine.parse("/bin/sh");
            cmdLine.addArgument("-c");
        }

        // 处理管道命令会报错，所以不解析
        cmdLine.addArgument(command, false);
        log.info("final command is: {}", command);

        DefaultExecutor executor = new DefaultExecutor();

        // 设置超时监控
        this.watchdog = new ExecuteWatchdog(TimeUnit.SECONDS.toMillis(timeoutSeconds));
        executor.setWatchdog(watchdog);

        // 配置输出流处理
        executor.setStreamHandler(new PumpStreamHandler(
                new LogOutputStream() {
                    @Override
                    protected void processLine(String line, int level) {
                        processOutputLine(line);
                    }
                },
                new LogOutputStream() {
                    @Override
                    protected void processLine(String line, int level) {
                        processErrorLine(line);
                    }
                }
        ));

        startTime = System.currentTimeMillis();
        try {
            CompletableFuture<Integer> future = CompletableFuture.supplyAsync(() -> {
                try {
                    return executor.execute(cmdLine);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            exitCode = future.get(timeoutSeconds, TimeUnit.SECONDS);
            log.info("Command executed successfully in {}s, command: {}", (System.currentTimeMillis() - startTime) / 1000, command);
        } catch (Exception e) {
            long endTime = System.currentTimeMillis();
            // 检查是否是超时引起的异常
            if (watchdog.killedProcess() || (endTime - startTime) >= TimeUnit.SECONDS.toMillis(timeoutSeconds)) {
                isTimeout.set(true);
                killProcess();
                log.info("Command timed out after {}s, command: {}", (endTime - startTime) / 1000, command);
            } else {
                // 其他异常处理
                if (e.getCause() instanceof IOException) {
                    log.error("IO error occurred while executing command: {}", command, e);
                    throw new StreamerRecordException(ErrorEnum.CMD_EXECUTE_ERROR);
                } else {
                    log.info("Command execution failed with exit code: {}, errorMsg: {}, command: {}", exitCode, e.getMessage(), command);
                    throw new StreamerRecordException(ErrorEnum.CMD_EXIT_CODE_UN_NORMAL);
                }
            }
        }
    }

    public void killProcess() {
        if (watchdog != null) {
            watchdog.destroyProcess();
        }
    }

    public boolean isTimeout() {
        return isTimeout.get();
    }

    /**
     * 判断命令是否是正常退出
     *
     * @return true 如果命令正常退出，false 如果命令超时、抛出异常或退出码非零
     */
    public boolean isNormalExit() {
        return !isTimeout.get() && exitCode == 0;
    }

    protected int getExitCode() {
        return exitCode;
    }

    protected long getStartTime() {
        return startTime;
    }

    /**
     * 处理正常输出
     *
     * @param line
     */
    protected abstract void processOutputLine(String line);

    /**
     * 处理错误输出
     *
     * @param line
     */
    protected abstract void processErrorLine(String line);
}