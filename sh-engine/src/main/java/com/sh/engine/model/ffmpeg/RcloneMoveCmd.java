package com.sh.engine.model.ffmpeg;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

@Slf4j
public class RcloneMoveCmd extends AbstractCmd {
    public RcloneMoveCmd(String fromFilePath, String toRemotePath) {
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
                fromFilePath,                    // 直接传：/home/admin/test file.mp4
                "alist_server:" + remoteTarget,  // 直接传：alist_server:/record/
                "--transfers", "1",
                "--contimeout", "10m",
                "--timeout", "80m",              // 核心：给跨境收尾 60 分钟耐心
                "--ignore-existing",             // 核心：防止因为重试导致分母翻倍
                "--size-only",                   // 建议：跨境传输只比对大小，省去 MD5 握手
                "--buffer-size", "64M",           // 核心：手动强制 64M 大分片，减少握手次数
                "--low-level-retries", "100",
                "--stats", "10s",
                "--stats-one-line",
                "-v"
        };
        return StringUtils.join(cmd, " ");
    }

    public static void main(String[] args) {
        String s = RcloneMoveCmd.buildCommand("/home/admin/stream/download/af-wannabe33/2026-04-17-21-50-27/P01.mp4", "/夸克云盘/test1.mp4");
        System.out.println(s);
    }
}
