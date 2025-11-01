package com.sh.engine.model.ffmpeg;

import java.io.File;

public class AssVideoMergeCmd extends AbstractCmd {
    private static final int CORE_COUNT = Runtime.getRuntime().availableProcessors();

    public AssVideoMergeCmd(File assFile, File videoFile) {
        super(buildCommand(assFile, videoFile));
    }

    @Override
    protected void processOutputLine(String line) {

    }

    @Override
    protected void processErrorLine(String line) {

    }


    /**
     * 构建FFmpeg合并命令
     * 参照命令：ffmpeg -i "P01.ts" -vf "subtitles=performance_test_danmaku.ass" -c:v libx264 -preset ultrafast -crf 28 -c:a copy -threads 4 -y "输出视频.mp4"
     */
    private static String buildCommand(File assFile, File videoFile) {
        String outputName = videoFile.getName().replace(".ts", ".mp4");
        File outputFile = new File(videoFile.getParentFile(), outputName);

        // 拼接FFmpeg命令
        return String.format(
                "ffmpeg -i %s -vf \"subtitles=%s\" -c:v libx264 -preset ultrafast -crf 28 -c:a copy -threads %s -y %s",
                "\"" + videoFile.getAbsolutePath() + "\"",
                "\"" + assFile.getAbsolutePath() + "\"",
                CORE_COUNT,
                "\"" + outputFile.getAbsolutePath() + "\""
        );
    }

    public static void main(String[] args) {
        File assFile = new File("G:\\stream_record\\download\\mytest-mac\\2025-11-01-21-21-50\\P01.ass");
        File videoFile = new File("G:\\stream_record\\download\\mytest-mac\\2025-11-01-21-21-50\\P01.ts");
        AssVideoMergeCmd cmd = new AssVideoMergeCmd(assFile, videoFile);
        cmd.execute(100 * 60);
    }
}
