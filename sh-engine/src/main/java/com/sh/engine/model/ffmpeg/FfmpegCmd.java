package com.sh.engine.model.ffmpeg;

import com.sh.config.utils.EnvUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.SystemUtils;

import java.io.InputStream;
import java.io.OutputStream;

/**
 * cmd方式调用ffmpeg
 */
@Slf4j
public class FfmpegCmd {
    /**
     * The process representing the ffmpeg execution.
     */
    private Process ffmpeg = null;

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

    /**
     * ffmpeg命令是否结束
     */
    private boolean ffmpegProcessEnd = false;

    public FfmpegCmd() {
    }

    public FfmpegCmd(String ffmpegCommand) {
        this.ffmpegCommand = ffmpegCommand;
    }

    public String getFfmpegCommand() {
        return this.ffmpegCommand;
    }

    /**
     * Executes the ffmpeg process with the previous given arguments.
     *
     * @param openIOStreams Open IO streams for input/output and errorout, should be false when
     *                      destroyOnRuntimeShutdown is false too
     */
    public void execute(boolean openIOStreams) {
//        String cmd = defaultFFMPEGLocator.getExecutablePath() + " " + ffmpegCommand;
//        String cmd = "ffmpeg" + " " + ffmpegCommand;
        String cmd = ffmpegCommand;
        log.info("final command is: {}", cmd);

        Runtime runtime = Runtime.getRuntime();
        try {
            if (SystemUtils.IS_OS_WINDOWS) {
                ffmpeg = runtime.exec(cmd);
            } else {
                ffmpeg = runtime.exec(new String[]{"sh", "-c", cmd});
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
        ffmpegProcessEnd = true;
        destroy();
    }

    public boolean isFfmpegProcessEnd() {
        return ffmpegProcessEnd;
    }
}