package com.sh.engine.model.ffmpeg;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

/**
 * 视频大小检查命令
 *
 * @Author caiwen
 * @Date 2024 10 26 10 52
 **/
@Slf4j
public class VideoSizeDetectCmd extends AbstractCmd {
    private final StringBuilder output = new StringBuilder();

    private int width;
    private int height;

    public VideoSizeDetectCmd(String filePath) {
        super("ffprobe -v error -select_streams v:0 -show_entries stream=width,height -of csv=p=0 " + filePath);
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

        // 执行完成后
        String[] split = StringUtils.trim(output.toString()).split("\n")[0].split(",");
        width = Integer.parseInt(split[0]);
        height = Integer.parseInt(split[1]);
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }
}
