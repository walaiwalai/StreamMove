package com.sh.engine.model.ffmpeg;

import com.sh.config.exception.ErrorEnum;
import com.sh.config.exception.StreamerRecordException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.exec.*;

import java.io.IOException;
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


    protected String command;

    protected AbstractCmd(String command) {
        this.command = command;
    }

    protected void execute(long timeoutSeconds) {
        CommandLine cmdLine = CommandLine.parse(command);
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

        long startTime = System.currentTimeMillis();
        try {
            exitCode = executor.execute(cmdLine);
            long endTime = System.currentTimeMillis();
            log.info("Command executed successfully in {}s, command: {}", (endTime - startTime) / 1000, command);
        } catch (ExecuteException e) {
            exitCode = e.getExitValue();
            long endTime = System.currentTimeMillis();
            if (watchdog.killedProcess()) {
                isTimeout.set(true);
                log.info("Command timed out after {}s, command: {}", (endTime - startTime) / 1000, command);
                handleTimeout();
            } else {
                log.info("Command execution failed with exit code: {}, command: {}", exitCode, command);
                throw new StreamerRecordException(ErrorEnum.CMD_EXIT_CODE_UN_NORMAL);
            }
        } catch (IOException e) {
            log.error("IO error occurred while executing command: {}", command, e);
            throw new StreamerRecordException(ErrorEnum.CMD_EXECUTE_ERROR);
        } catch (Exception e) {
            log.error("Unexpected error occurred while executing command: {}", command, e);
            throw new StreamerRecordException(ErrorEnum.CMD_EXECUTE_ERROR);

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

    /**
     * 可选的超时处理钩子方法
     */
    protected void handleTimeout() {
    }
}
