package com.sh.engine.model.ffmpeg;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.SystemUtils;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * 通用的命令处理
 *
 * @Author caiwen
 * @Date 2024 10 25 22 40
 **/
@Slf4j
public abstract class CommonCmd {
    /**
     * 进程
     */
    private Process pr = null;

    /**
     * 是否开启输出
     */
    private boolean printInfo;
    private boolean printError;

    /**
     * 流
     */
    private InputStream inputStream = null;
    private OutputStream outputStream = null;
    private InputStream errorStream = null;

    /**
     * 执行的命令
     */
    private String command;

    /**
     * 进程是否结束
     */
    private boolean prEnd = false;

    public CommonCmd(String command, boolean printInfo, boolean printError) {
        this.command = command;
        this.printInfo = printInfo;
        this.printError = printError;
    }

    protected abstract void doExecute();

    /**
     * 执行命令
     */
    public void execute() {
        try {
            doExecute();
        } finally {
            close();
        }
    }

    /**
     * Executes the ffmpeg process with the previous given arguments.
     */
    protected void start(Consumer<String> iCon, Consumer<String> eCon) {
        log.info("final command is: {}", command);

        Runtime runtime = Runtime.getRuntime();
        try {
            if (SystemUtils.IS_OS_WINDOWS) {
                pr = runtime.exec(new String[]{"cmd.exe", "/c", command});
            } else {
                pr = runtime.exec(new String[]{"sh", "-c", command});
            }

            inputStream = pr.getInputStream();
            errorStream = pr.getErrorStream();
            CompletableFuture.runAsync(() -> {
                BufferedReader br = new BufferedReader(new InputStreamReader(inputStream));
                String line = null;
                try {
                    while ((line = br.readLine()) != null) {
                        if (printInfo) {
                            log.info("OT-STREAM>>>>" + line);
                        }
                        if (iCon != null) {
                            iCon.accept(line);
                        }
                    }
                } catch (Exception e) {
                }
            });

            CompletableFuture.runAsync(() -> {
                BufferedReader br = new BufferedReader(new InputStreamReader(errorStream));
                String line = null;
                try {
                    while ((line = br.readLine()) != null) {
                        if (printError) {
                            log.info("ER-STREAM>>>>" + line);
                        }
                        if (eCon != null) {
                            eCon.accept(line);
                        }
                    }
                } catch (Exception e) {
                }
            });
        } catch (Exception e) {
            log.error("run ffmpeg error!, cmd: {}", command, e);
        }
    }

    /**
     * Returns a stream reading from the ffmpeg process standard output channel.
     *
     * @return A stream reading from the ffmpeg process standard output channel.
     */
    protected InputStream getInputStream() {
        return inputStream;
    }

    /**
     * Returns a stream writing in the ffmpeg process standard input channel.
     *
     * @return A stream writing in the ffmpeg process standard input channel.
     */
    protected OutputStream getOutputStream() {
        return outputStream;
    }

    /**
     * Returns a stream reading from the ffmpeg process standard error channel.
     *
     * @return A stream reading from the ffmpeg process standard error channel.
     */
    protected InputStream getErrorStream() {
        return errorStream;
    }

    /**
     * If there's a ffmpeg execution in progress, it kills it.
     */
    private void destroy() {
        if (inputStream != null) {
            try {
                inputStream.close();
            } catch (Throwable t) {
                log.warn("Error closing input stream", t);
            }
            inputStream = null;
        }

        if (outputStream != null) {
            try {
                outputStream.close();
            } catch (Throwable t) {
                log.warn("Error closing output stream", t);
            }
            outputStream = null;
        }

        if (errorStream != null) {
            try {
                errorStream.close();
            } catch (Throwable t) {
                log.warn("Error closing error stream", t);
            }
            errorStream = null;
        }

        if (pr != null) {
            pr.destroy();
            pr = null;
        }
    }

    /**
     * Return the exit code of the ffmpeg process If the process is not yet terminated, it waits for
     * the termination of the process
     *
     * @return process exit code
     */
    protected int getPrExitCode() {
        // Make sure it's terminated
        try {
            pr.waitFor();
        } catch (InterruptedException ex) {
            log.warn("Interrupted during waiting on process, forced shutdown", ex);
        }
        int code = pr.exitValue();
        if (code != 0) {
            log.warn("process exit code: {}, command: {}", code, command);
        }
        return code;
    }

    /**
     * close
     **/
    protected void close() {
        prEnd = true;
        destroy();
    }

    public boolean isPrEnd() {
        return prEnd;
    }
}
