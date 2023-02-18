package com.sh.engine.model.ffmpeg;

import lombok.extern.slf4j.Slf4j;
import ws.schild.jave.process.ProcessKiller;
import ws.schild.jave.process.ffmpeg.DefaultFFMPEGLocator;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * @Description:(cmd方式调用ffmpeg)
 * @author: HeShengjin
 * @date: 2021年6月22日 下午5:31:38
 * @Copyright:
 */
@Slf4j
public class FfmpegCmd {
    /**
     * The process representing the ffmpeg execution.
     */
    private Process ffmpeg = null;

    /**
     * A process killer to kill the ffmpeg process with a shutdown hook, useful if the jvm execution
     * is shutted down during an ongoing encoding process.
     */
    private ProcessKiller ffmpegKiller = null;

    /**
     * A stream reading from the ffmpeg process standard output channel.
     */
    private InputStream inputStream = null;

    /**
     * A stream writing in the ffmpeg process standard input channel.
     */
    private OutputStream outputStream = null;

    /**
     * A stream reading from the ffmpeg process standard error channel.
     */
    private InputStream errorStream = null;

    /**
     * 执行的命令
     */
    private String ffmpegCommand;

    public FfmpegCmd() {}

    public FfmpegCmd(String ffmpegCommand) {
        this.ffmpegCommand = ffmpegCommand;
    }

    public String getFfmpegCommand() {
        return this.ffmpegCommand;
    }

    /**
     * Executes the ffmpeg process with the previous given arguments.
     *
     * @param destroyOnRuntimeShutdown destroy process if the runtime VM is shutdown
     * @param openIOStreams            Open IO streams for input/output and errorout, should be false when
     *                                 destroyOnRuntimeShutdown is false too
     *                                 " -i C:\\Users\\hsj\\AppData\\Local\\Temp\\jave\\honer.mp4 -c copy
     *                                 C:\\Users\\hsj\\AppData\\Local\\Temp\\jave\\honer_test.mov "
     * @throws IOException If the process call fails.
     */
    public void execute(boolean destroyOnRuntimeShutdown, boolean openIOStreams) throws IOException {
        DefaultFFMPEGLocator defaultFFMPEGLocator = new DefaultFFMPEGLocator();
        String cmd = defaultFFMPEGLocator.getExecutablePath() + " " + ffmpegCommand;
        log.info("ffmpegCmd final is: {}", cmd);

        Runtime runtime = Runtime.getRuntime();
        try {
            ffmpeg = runtime.exec(new String[]{"sh", "-c", cmd});

            if (destroyOnRuntimeShutdown) {
                ffmpegKiller = new ProcessKiller(ffmpeg);
                runtime.addShutdownHook(ffmpegKiller);
            }

            if (openIOStreams) {
                inputStream = ffmpeg.getInputStream();
                outputStream = ffmpeg.getOutputStream();
                errorStream = ffmpeg.getErrorStream();
            }
        } catch (Exception e) {
            log.error("run ffmpeg error!, cmd: {}", cmd, e);
        }
    }

    /**
     * Returns a stream reading from the ffmpeg process standard output channel.
     *
     * @return A stream reading from the ffmpeg process standard output channel.
     */
    public InputStream getInputStream() {
        return inputStream;
    }

    /**
     * Returns a stream writing in the ffmpeg process standard input channel.
     *
     * @return A stream writing in the ffmpeg process standard input channel.
     */
    public OutputStream getOutputStream() {
        return outputStream;
    }

    /**
     * Returns a stream reading from the ffmpeg process standard error channel.
     *
     * @return A stream reading from the ffmpeg process standard error channel.
     */
    public InputStream getErrorStream() {
        return errorStream;
    }

    /**
     * If there's a ffmpeg execution in progress, it kills it.
     */
    public void destroy() {
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

        if (ffmpeg != null) {
            ffmpeg.destroy();
            ffmpeg = null;
        }

        if (ffmpegKiller != null) {
            Runtime runtime = Runtime.getRuntime();
            runtime.removeShutdownHook(ffmpegKiller);
            ffmpegKiller = null;
        }
    }

    /**
     * Return the exit code of the ffmpeg process If the process is not yet terminated, it waits for
     * the termination of the process
     *
     * @return process exit code
     */
    public int getProcessExitCode() {
        // Make sure it's terminated
        try {
            ffmpeg.waitFor();
        } catch (InterruptedException ex) {
            log.warn("Interrupted during waiting on process, forced shutdown?", ex);
        }
        return ffmpeg.exitValue();
    }

    /**
     * close
     **/
    public void close() {
        destroy();
    }

}