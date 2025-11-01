package com.sh.engine.model.ffmpeg;

import com.sh.engine.model.video.StreamMetaInfo;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

@Slf4j
public class StreamMetaDetectCmd extends AbstractCmd {
    private final StringBuilder output = new StringBuilder();
    private StreamMetaInfo metaInfo = new StreamMetaInfo();

    public StreamMetaDetectCmd(String streamUrl) {
        super(buildCommand(streamUrl));
    }

    /**
     * 构建跨平台的命令
     */
    private static String buildCommand(String streamUrl) {
        String quotedPath = "\"" + streamUrl + "\"";
        return "ffprobe -i " + quotedPath + " -v error -show_streams";
    }

    @Override
    protected void processOutputLine(String line) {
        output.append(line).append("\n");
    }

    @Override
    protected void processErrorLine(String line) {
        log.warn("ffprobe error output: {}", line);
    }

    public void execute(long timeoutSeconds) {
        super.execute(timeoutSeconds);

        String outputStr = StringUtils.trim(output.toString());
        if (StringUtils.isEmpty(outputStr)) {
            return;
        }

        // 按行分割输出内容，逐行解析
        String[] lines = outputStr.split("\n");
        for (String line : lines) {
            line = StringUtils.trim(line);
            if (line.startsWith("width=")) {
                try {
                    String widthStr = StringUtils.substringAfter(line, "width=");
                    this.metaInfo.setWidth(Integer.parseInt(widthStr));
                } catch (NumberFormatException e) {
                    log.error("parse width error, line: {}", line, e);
                }
            } else if (line.startsWith("height=")) {
                try {
                    String heightStr = StringUtils.substringAfter(line, "height=");
                    this.metaInfo.setHeight(Integer.parseInt(heightStr));
                } catch (NumberFormatException e) {
                    log.error("parse height error, line: {}", line, e);
                }
            }
        }
    }

    public StreamMetaInfo getMetaInfo() {
        return metaInfo;
    }
}