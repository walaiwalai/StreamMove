package com.sh.engine.model.ffmpeg;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

@Slf4j
public class RcloneCopyCmd extends AbstractCmd {
    public RcloneCopyCmd(String fromFilePath, String toRemotePath) {
        super(buildCommand(fromFilePath, toRemotePath));
    }

    @Override
    protected void processOutputLine(String line) {
        log.info("rclone-copy info>>>> {}", line);
    }

    @Override
    protected void processErrorLine(String line) {
        log.error("rclone-copy error >>>> {}", line);
    }

    private static String buildCommand(String fromFilePath, String toRemotePath) {
        String[] cmd = new String[]{
                "rclone", "copy",
                fromFilePath,
                "alist_server:" + toRemotePath,
                "--transfers", "1",
                "--buffer-size", "1G",
                "--use-mmap",
                "--drive-chunk-size", "128M",
                "--multi-thread-streams", "4",
                "--retries", "10",
                "--low-level-retries", "10",
                "--stats", "10s",
                "--stats-one-line",
                "--log-level", "NOTICE"
        };
        return StringUtils.join(cmd, " ");
    }
}
