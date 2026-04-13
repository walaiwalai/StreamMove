package com.sh.engine.model.ffmpeg;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

@Slf4j
public class RcloneCopyCmd extends AbstractCmd {
    public RcloneCopyCmd(String fromFilePath, String toRemotePath) {
        // 注意：此处建议给 toRemotePath 加引号，防止路径中有空格或特殊字符
        super(buildCommand(fromFilePath, toRemotePath));
    }

    @Override
    protected void processOutputLine(String line) {
        if (StringUtils.isNotBlank(line)) {
            log.info("rclone-stdout > {}", line.trim());
        }
    }

    @Override
    protected void processErrorLine(String line) {
        if (StringUtils.isBlank(line)) return;
        // 增加对 "Attempt" 的判定，这样你能看到它是第几次重试
        if (line.contains("INFO") || line.contains("%") || line.contains("ETA") || line.contains("Attempt")) {
            log.info("rclone-progress > {}", line.trim());
        } else {
            log.error("rclone-real-error > {}", line.trim());
        }
    }

    private static String buildCommand(String fromFilePath, String toRemotePath) {
        String remoteTarget = toRemotePath.endsWith("/") ? toRemotePath : toRemotePath + "/";

        String[] cmd = new String[]{
                "rclone", "copy",
                "\"" + fromFilePath + "\"",
                "alist_server:\"" + remoteTarget + "\"",
                "--transfers", "1",
                "--buffer-size", "32M",
                "--use-mmap",

                // --- 核心传输逻辑 (移除所有报错的特定参数) ---
                "--multi-thread-streams", "0",

                // --- 你的重试与超时控制
                "--timeout", "15m",
                "--contimeout", "2m",
                "--retries", "10",
                "--low-level-retries", "20",

                // --- 覆盖策略优化 ---
                "--update",
                "--stats", "10s",
                "--stats-one-line",
                "-v"
        };
        return StringUtils.join(cmd, " ");
    }
}
