package com.sh.engine.model.ffmpeg;

import com.sh.engine.constant.RecordConstant;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.SystemUtils;

import java.io.File;

@Slf4j
public class AssVideoMergeCmd extends AbstractCmd {
    // 字符串前缀常量（避免重复创建）
    private static final String OUT_TIME_PREFIX = "out_time=";
    private static final String SPEED_PREFIX = "speed=";

    private static final int CORE_COUNT = Runtime.getRuntime().availableProcessors();
    // 计数阈值：每累计200次打印一次
    private static final int PRINT_THRESHOLD = 500;
    // 累计计数器
    private int matchCount = 0;
    private String latestOutTime = "";
    private String latestSpeed = "";

    public AssVideoMergeCmd(File assFile, File mp4File) {
        super(buildCommand(assFile, mp4File));
    }

    @Override
    protected void processOutputLine(String line) {
        if (line == null) {
            return;
        }
        if (line.startsWith(OUT_TIME_PREFIX)) {
            latestOutTime = line.substring(OUT_TIME_PREFIX.length()).trim();
            matchCount++;
        } else if (line.startsWith(SPEED_PREFIX)) {
            latestSpeed = line.substring(SPEED_PREFIX.length()).trim();
            matchCount++;
        }

        // 达到阈值时打印
        if (matchCount >= PRINT_THRESHOLD) {
            long costSeconds = (System.currentTimeMillis() - getStartTime()) / 1000;
            log.info("merge detail show, out_time: {}, speed: {}, cost: {}s",
                    latestOutTime, latestSpeed, costSeconds);
            matchCount = 0;
        }
    }

    @Override
    protected void processErrorLine(String line) {
    }


    /**
     * 构建FFmpeg合并命令
     * 参照命令：ffmpeg -i "P01.ts" -vf "subtitles=performance_test_danmaku.ass" -c:v libx264 -preset ultrafast -crf 28 -c:a copy -threads 4 -y "输出视频.mp4"
     */
    private static String buildCommand(File assFile, File mp4File) {
        File outputFile = new File(mp4File.getParentFile(), RecordConstant.DAMAKU_FILE_PREFIX + mp4File.getName());

        // 拼接FFmpeg命令
        return String.format(
                "ffmpeg -progress pipe:1 -i %s -vf \"subtitles=%s\" -c:v libx264 -preset superfast -crf 26 -c:a copy -threads %s -y %s",
                "\"" + mp4File.getAbsolutePath() + "\"",
                processAssPath(assFile.getAbsolutePath()),
                CORE_COUNT,
                "\"" + outputFile.getAbsolutePath() + "\""
        );
    }

    /**
     * 处理ASS文件路径（Windows特殊处理）
     * - Windows：1. 反斜杠转义为双反斜杠 2. 驱动器号冒号转义（G: → G\:） 3. 用单引号包裹路径
     * - Linux：保持路径，用单引号包裹（兼容处理）
     */
    private static String processAssPath(String rawPath) {
        if (SystemUtils.IS_OS_WINDOWS) {
            // 1. 转义反斜杠（\ → \\）
            String escaped = rawPath.replace("\\", "\\\\");
            // 2. 转义驱动器号冒号（: → \:，如 G: → G\:）
            escaped = escaped.replace(":", "\\:");
            // 3. 用单引号包裹路径（避免命令行解析错误）
            return "'" + escaped + "'";
        } else {
            // Linux：用单引号包裹路径（处理含空格/特殊字符的情况）
            return "'" + rawPath + "'";
        }
    }

    public static void main(String[] args) {
        File assFile = new File("G:\\动漫\\凡人\\performance_test_danmaku.ass");
        File videoFile = new File("G:\\动漫\\凡人\\S01E166.2160p.WEB-DL.H264.AAC.mp4");
        AssVideoMergeCmd cmd = new AssVideoMergeCmd(assFile, videoFile);
        cmd.execute(100 * 60);
    }
}
