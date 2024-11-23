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
public class VideoSizeDetectCmd extends CommonCmd {
    private int width;
    private int height;

    public VideoSizeDetectCmd(String command) {
        super(command, true, true);
    }

    @Override
    protected void doExecute() {
        StringBuilder output = new StringBuilder();
        super.start((line) -> {
            output.append(line).append("\n");
        }, null);

        super.getPrExitCode();
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
        }

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

    public static void main(String[] args) {
        String querySizeCmd = "ffprobe -v error -select_streams v:0 -show_entries stream=width,height -of csv=p=0 G:\\stream_record\\download\\TheShy\\2024-10-26-10-19-00\\seg-00001.ts";

        VideoSizeDetectCmd detectCmd = new VideoSizeDetectCmd(querySizeCmd);
        detectCmd.execute();

        System.out.println(detectCmd.getWidth());
        System.out.println(detectCmd.getHeight());
    }
}
