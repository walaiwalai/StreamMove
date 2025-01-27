package com.sh.engine.model.ffmpeg;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

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

    public VideoSizeDetectCmd(String filePath) {
        super("ffprobe -v error -select_streams v:0 -show_entries stream=width,height -of csv=p=0 " + filePath, true, true);
    }

    @Override
    protected void doExecute(long timeout, TimeUnit unit) throws Exception {
        // 等待命令执行完成
        StringBuilder output = new StringBuilder();
        CompletableFuture<Void> future = super.start((line) -> {
            output.append(line).append("\n");
        }, null);
        future.get(timeout, unit);
        super.waitExit();

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
        File file = new File("G:\\stream_record\\download\\TheShy\\2025-01-24-23-54-30\\seg-00001.ts");
        VideoSizeDetectCmd detectCmd = new VideoSizeDetectCmd(file.getAbsolutePath());
        detectCmd.execute(2, TimeUnit.SECONDS);

        System.out.println(detectCmd.getWidth());
        System.out.println(detectCmd.getHeight());
    }
}
