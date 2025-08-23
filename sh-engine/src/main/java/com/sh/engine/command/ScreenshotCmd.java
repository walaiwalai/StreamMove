package com.sh.engine.command;

import cn.hutool.core.io.FileUtil;
import com.google.common.collect.Lists;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @Author caiwen
 * @Date 2025 08 16 09 50
 **/
@Slf4j
public class ScreenshotCmd extends AbstractCmd {
    private static final int CORE_COUNT = Runtime.getRuntime().availableProcessors();

    private final File sourceFile;
    private final File snapShotDir;
    private List<File> snapshotFiles = new ArrayList<>();

    /**
     * 截图命令
     *
     * @param sourceFile      视频源文件
     * @param snapShotDir     截图保存目录
     * @param ss              视频截图开始时间
     * @param snapShotCnt     截图数量
     * @param corpExp         裁剪表达式
     * @param intervalSeconds 截图间隔
     * @param startIndex      截图开始索引
     */
    public ScreenshotCmd(File sourceFile, File snapShotDir, int ss, int snapShotCnt, String corpExp, int intervalSeconds, int startIndex, boolean isAccurateLocation) {
        super(buildCommand(sourceFile, snapShotDir, ss, snapShotCnt, corpExp, intervalSeconds, startIndex, isAccurateLocation));
        this.sourceFile = sourceFile;
        this.snapShotDir = snapShotDir;
    }

    /**
     * 构建ffmpeg截图命令
     */
    private static String buildCommand(File sourceFile, File snapShotDir, int ss, int snapShotCnt, String corpExp, int intervalSeconds, int startIndex, boolean isAccurateLocation) {
        // 构建截图目标文件路径
        String targetFilePath = new File(snapShotDir, FileUtil.getPrefix(sourceFile) + "#%d.jpg").getAbsolutePath();
        List<String> params;
        if (isAccurateLocation) {
            // --ss 放在-i之后用于精准定位，会完整解码从视频开头到目标时间点的所有帧，确保准确定位到 480 秒的精确画面，误差可控制在毫秒级。
            params = Lists.newArrayList(
                    "ffmpeg", "-y",
                    "-i", "\"" + sourceFile.getAbsolutePath() + "\"",
                    "-ss", String.valueOf(ss),
                    "-vf", corpExp + ",fps=1/" + intervalSeconds + ",format=yuv420p",
                    "-start_number", String.valueOf(startIndex),
                    "-vframes", String.valueOf(snapShotCnt),
                    "\"" + targetFilePath + "\""
            );
        } else {
            // --ss 放在-i之前快速，FFmpeg 会直接根据视频的索引信息（如关键帧）跳转到目标时间点，无需解码 解码前面的所有帧
            params = Lists.newArrayList(
                    "ffmpeg", "-y",
                    "-ss", String.valueOf(ss),
                    "-threads", String.valueOf(CORE_COUNT),
                    "-i", "\"" + sourceFile.getAbsolutePath() + "\"",
                    "-vf", corpExp + ",fps=1/" + intervalSeconds + ",format=yuv420p",
                    "-start_number", String.valueOf(startIndex),
                    "-vframes", String.valueOf(snapShotCnt),
                    "\"" + targetFilePath + "\""
            );
        }
        return StringUtils.join(params, " ");
    }

    @Override
    protected void processOutputLine(String line) {
    }

    @Override
    protected void processErrorLine(String line) {
    }

    public void execute(long timeoutSeconds) {
        super.execute(timeoutSeconds);
        // 命令执行完成后，查找生成的截图文件
        findSnapshotFiles();
    }

    /**
     * 查找生成的截图文件
     */
    private void findSnapshotFiles() {
        String prefix = FileUtil.getPrefix(sourceFile);
        Pattern pattern = Pattern.compile(prefix + "#(\\d+)\\.jpg");

        // 遍历目录查找匹配的文件
        File[] files = snapShotDir.listFiles();
        if (files != null) {
            for (File file : files) {
                Matcher matcher = pattern.matcher(file.getName());
                if (matcher.matches()) {
                    snapshotFiles.add(file);
                }
            }
        }
        log.info("screenshot {} pic success，save at: {}", snapshotFiles.size(), snapShotDir.getAbsolutePath());
    }

    /**
     * 获取生成的截图文件列表
     */
    public List<File> getSnapshotFiles() {
        return new ArrayList<>(snapshotFiles);
    }

    public static void main(String[] args) {
        File sourceFile = new File("G:\\stream_record\\download\\mytest-mac\\2025-08-15-20-59-48\\P01.mp4");
        File snapShotDir = new File("G:\\stream_record\\download\\mytest-mac\\2025-08-15-20-59-48\\kda-test-snapshot");
        ScreenshotCmd cmd = new ScreenshotCmd(sourceFile, snapShotDir, 0, 99999, "crop=270:290:in_w*86/100:in_h*3/16", 4, 1, false);
        cmd.execute(100);
    }
}
