package com.sh.engine.model.ffmpeg;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

/**
 * 视频时长检查命令（返回整数秒数）
 *
 * @Author caiwen
 * @Date 2024 10 26 10 52
 **/
@Slf4j
public class VideoDurationDetectCmd extends AbstractCmd {
    private final StringBuilder output = new StringBuilder();
    private double durationSeconds;

    public VideoDurationDetectCmd(String filePath) {
        super(buildCommand(filePath));
    }

    /**
     * 构建跨平台的命令
     */
    private static String buildCommand(String filePath) {
        String quotedPath = "\"" + filePath + "\"";
        return "ffprobe -v error -show_entries format=duration -of default=noprint_wrappers=1:nokey=1 " + quotedPath;
    }

    @Override
    protected void processOutputLine(String line) {
        output.append(line).append("\n");
    }

    @Override
    protected void processErrorLine(String line) {
    }

    public void execute(long timeoutSeconds) {
        super.execute(timeoutSeconds);

        String durationStr = StringUtils.trim(output.toString());
        if (StringUtils.isNotBlank(durationStr)) {
            try {
                durationSeconds = Double.parseDouble(durationStr);
            } catch (NumberFormatException e) {
                log.error("解析视频时长失败，输出内容: {}", durationStr, e);
                durationSeconds = 0;
            }
        } else {
            log.warn("未获取到视频时长信息");
            durationSeconds = 0;
        }
    }

    /**
     * 获取视频时长（整数秒数，四舍五入）
     */
    public double getDurationSeconds() {
        return durationSeconds;
    }
}