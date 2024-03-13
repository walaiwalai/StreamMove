package com.sh.engine.service;

import com.google.common.collect.Lists;
import com.sh.engine.model.ffmpeg.FfmpegCmd;
import com.sh.engine.util.CommandUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;


/***
 * todo 代做事项
 * 2. 背景音乐
 * 3. 视频开场和结尾加上动画
 */
@Component
@Slf4j
public class VideoMergeServiceImpl implements VideoMergeService {
    @Resource
    MsgSendService msgSendService;

    /**
     * 淡出时间
     */
    private static final int FADE_DURATION = 1;

    @Override
    public boolean merge(List<String> mergedFileNames, File targetVideo) {
        File mergeListFile = new File(targetVideo.getParent(), "merge.txt");
        List<String> lines = mergedFileNames.stream()
                .map(name -> {
                    File segFile = new File(name);
                    if (!segFile.exists()) {
                        return null;
                    }
                    return "file " + segFile.getAbsolutePath();
                })
//                .map(s -> s.replace("\\", "\\\\"))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        // 1. 写入merge.txt
        try {
            IOUtils.write(StringUtils.join(lines, "\n"), new FileOutputStream(mergeListFile), "utf-8");
        } catch (IOException e) {
            log.error("write merge list file fail, savePath: {}", mergeListFile.getAbsolutePath(), e);
        }

        // 2. 使用FFmpeg合并视频
        String targetPath = targetVideo.getAbsolutePath();
        String command = "-f concat -safe 0 -i " + mergeListFile.getAbsolutePath() +
//                " -c:v libx264 -crf 24 -preset superfast -c:a libfdk_aac -r 30 " + targetPath;
                " -c:v copy -c:a copy -r 60 " + targetPath;
        FfmpegCmd ffmpegCmd = new FfmpegCmd(command);

        Integer resCode = CommandUtil.cmdExec(ffmpegCmd);
        if (resCode == 0) {
            msgSendService.send("合并压缩视频完成！路径为：" + targetVideo.getAbsolutePath());
            log.info("merge video success, path: {}", targetPath);
            return true;
        } else {
            msgSendService.send("压缩视频失败！路径为：" + targetVideo.getAbsolutePath());
            log.info("merge video fail, path: {}", targetPath);
            return false;
        }
    }

    @Override
    public boolean mergeMulti(List<List<String>> intervals, File targetVideo) {
        // 单独一个不处理
        if (intervals.size() == 1) {
            return merge(intervals.get(0), targetVideo);
        }

        String targetPath = targetVideo.getAbsolutePath();
        File segMergeFile = new File(targetVideo.getParent(), "seg-merge");
        if (!segMergeFile.exists()) {
            segMergeFile.mkdir();
        }

        // 先合并小的seg文件
        List<String> videoFilePaths = Lists.newArrayList();
        for (int i = 0; i < intervals.size(); i++) {
            List<String> interval = intervals.get(i);
            String mergedName = "merged-" + i + ".mp4";
            File segFile = new File(segMergeFile, mergedName);
            if (merge(interval, segFile)) {
                videoFilePaths.add(segFile.getAbsolutePath());
            }
        }

        // 合并视频，加入淡入/淡出效果
        FfmpegCmd ffmpegCmd = new FfmpegCmd(buildMergeVideoCommand(videoFilePaths, targetVideo));
        Integer resCode = CommandUtil.cmdExec(ffmpegCmd);
        if (resCode == 0) {
//            msgSendService.send("制作精彩剪辑成功！路径为：" + targetPath);
            log.info("merge highlight video success, path: {}", targetPath);
            return true;
        } else {
//            msgSendService.send("制作精彩剪辑失败！路径为：" + targetPath);
            log.info("merge highlight video fail, path: {}", targetPath);
            return false;
        }
    }

    /**
     * @param videoFilePaths
     * @return
     */
    private static String buildMergeVideoCommand(List<String> videoFilePaths, File targetVideo) {
        StringBuilder ffmpegCommand = new StringBuilder("-y ");
        // 添加输入文件路径
        for (int i = 0; i < videoFilePaths.size(); i++) {
            ffmpegCommand.append("-i \"").append(videoFilePaths.get(i)).append("\" ");
        }

        // 视频淡入/淡出效果（假设对每个视频片段都做淡入）
        ffmpegCommand.append("-filter_complex \"");
        for (int i = 1; i < videoFilePaths.size(); i++) {
            ffmpegCommand.append("[")
                    .append(i)
                    .append(":v]fade=t=in:st=0:d=" + FADE_DURATION + "[v")
                    .append(i)
                    .append("];");
        }

        // 合并视频流
        ffmpegCommand.append("[0:v]");
        for (int i = 1; i < videoFilePaths.size(); i++) {
            ffmpegCommand.append("[");
            ffmpegCommand.append("v")
                    .append(i)
                    .append("]");
        }
        ffmpegCommand.append("concat=n=").append(videoFilePaths.size()).append(":v=1:a=0[outv];");

        // 合并音频流
        for (int i = 0; i < videoFilePaths.size(); i++) {
            ffmpegCommand.append("[").append(i).append(":a]");
        }
        ffmpegCommand.append("concat=n=").append(videoFilePaths.size()).append(":v=0:a=1[outa]\" ");

        // 输出映射
        ffmpegCommand.append("-map \"[outv]\" ");
        ffmpegCommand.append("-map \"[outa]\" ");

        // 编码参数设置
        ffmpegCommand.append("-c:v libx264 -crf 23 -preset superfast ");
        ffmpegCommand.append("-c:a aac ");

        // 输出文件路径
        ffmpegCommand.append(targetVideo.getAbsolutePath());

        return ffmpegCommand.toString();
    }

    public static void main(String[] args) {
        List<String> segs1 = Lists.newArrayList(
                "F:\\video\\download\\TheShy\\2024-01-31-03-31-43\\seg-761.ts",
                "F:\\video\\download\\TheShy\\2024-01-31-03-31-43\\seg-762.ts",
                "F:\\video\\download\\TheShy\\2024-01-31-03-31-43\\seg-763.ts",
                "F:\\video\\download\\TheShy\\2024-01-31-03-31-43\\seg-764.ts",
                "F:\\video\\download\\TheShy\\2024-01-31-03-31-43\\seg-765.ts",
                "F:\\video\\download\\TheShy\\2024-01-31-03-31-43\\seg-766.ts"
        );

        List<String> segs2 = Lists.newArrayList(
                "F:\\video\\download\\TheShy\\2024-01-31-03-31-43\\seg-700.ts",
                "F:\\video\\download\\TheShy\\2024-01-31-03-31-43\\seg-701.ts",
                "F:\\video\\download\\TheShy\\2024-01-31-03-31-43\\seg-702.ts",
                "F:\\video\\download\\TheShy\\2024-01-31-03-31-43\\seg-703.ts",
                "F:\\video\\download\\TheShy\\2024-01-31-03-31-43\\seg-704.ts",
                "F:\\video\\download\\TheShy\\2024-01-31-03-31-43\\seg-705.ts"
        );

        List<String> segs3 = Lists.newArrayList(
                "F:\\video\\download\\TheShy\\2024-01-31-03-31-43\\seg-2260.ts",
                "F:\\video\\download\\TheShy\\2024-01-31-03-31-43\\seg-2261.ts",
                "F:\\video\\download\\TheShy\\2024-01-31-03-31-43\\seg-2262.ts",
                "F:\\video\\download\\TheShy\\2024-01-31-03-31-43\\seg-2263.ts",
                "F:\\video\\download\\TheShy\\2024-01-31-03-31-43\\seg-2264.ts",
                "F:\\video\\download\\TheShy\\2024-01-31-03-31-43\\seg-2265.ts"
        );
        String outputFilePath = "F:\\video\\download\\TheShy\\2024-01-31-03-31-43\\aaa.mp4";
        VideoMergeServiceImpl videoMergeService = new VideoMergeServiceImpl();
        videoMergeService.mergeMulti(Lists.newArrayList(segs1, segs2, segs3), new File(outputFilePath));
    }
}

