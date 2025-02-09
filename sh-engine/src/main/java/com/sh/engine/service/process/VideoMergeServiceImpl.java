package com.sh.engine.service.process;

import cn.hutool.core.io.file.FileNameUtil;
import com.google.common.collect.Lists;
import com.sh.config.utils.PictureFileUtil;
import com.sh.engine.model.ffmpeg.FFmpegProcessCmd;
import com.sh.engine.model.ffmpeg.VideoSizeDetectCmd;
import com.sh.message.service.MsgSendService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.SystemUtils;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;


/***
 * https://trac.ffmpeg.org/wiki/Concatenate#demuxer
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
     * 淡出时间（s）
     */
    private static final int FADE_DURATION = 1;

    @Override
    public boolean concatByDemuxer(List<String> mergedFileNames, File targetVideo) {
        File mergeListFile = new File(targetVideo.getParent(), FileNameUtil.getPrefix(targetVideo) + "-merge.txt");
        List<String> lines = mergedFileNames.stream()
                .map(name -> {
                    File segFile = new File(name);
                    if (!segFile.exists()) {
                        return null;
                    }
                    return "file " + segFile.getAbsolutePath();
                })
                .filter(Objects::nonNull)
                .map(s -> SystemUtils.IS_OS_WINDOWS ? s.replace("\\", "\\\\") : s)
                .collect(Collectors.toList());

        if (CollectionUtils.isEmpty(lines)) {
            return false;
        }

        // 1. 写入merge.txt
        try {
            IOUtils.write(StringUtils.join(lines, "\n"), new FileOutputStream(mergeListFile), "utf-8");
        } catch (IOException e) {
            log.error("write merge list file fail, savePath: {}", mergeListFile.getAbsolutePath(), e);
        }

        // 2. 使用FFmpeg合并视频
        String targetPath = targetVideo.getAbsolutePath();
        String command = "ffmpeg -y -loglevel error -f concat -safe 0 -i " + mergeListFile.getAbsolutePath() +
                " -c:v copy -c:a copy " + targetPath;
        FFmpegProcessCmd processCmd = new FFmpegProcessCmd(command);
        processCmd.execute(3 * 3600L);
        return processCmd.isEndNormal();
    }

    @Override
    public boolean concatByProtocol(List<String> mergedFileNames, File targetVideo) {
        if (CollectionUtils.isEmpty(mergedFileNames)) {
            return false;
        }
        String targetPath = targetVideo.getAbsolutePath();
        String cmd = "ffmpeg -loglevel error -i " + "concat:" + StringUtils.join(mergedFileNames, "|") + " -c copy " + targetPath;
        FFmpegProcessCmd processCmd = new FFmpegProcessCmd(cmd);
        processCmd.execute(3 * 3600L);
        if (processCmd.isEndNormal()) {
            msgSendService.sendText("按照concat协议合并视频完成！路径为：" + targetPath);
            return true;
        } else {
            msgSendService.sendText("按照concat协议合并视频失败！路径为：" + targetPath);
            return false;
        }
    }

    @Override
    public boolean mergeMultiWithFade(List<List<String>> intervals, File targetVideo) {
        // 单独一个不处理
        if (intervals.size() == 1) {
            return concatByDemuxer(intervals.get(0), targetVideo);
        }

        // 先合并小的seg文件
        List<String> segVideoPaths = Lists.newArrayList();
        for (int i = 0; i < intervals.size(); i++) {
            if (i == 0) {
                segVideoPaths.addAll(intervals.get(i));
                continue;
            }

            // 针对片段开头的视频做淡入操作
            List<String> interval = intervals.get(i);
            for (int j = 0; j < interval.size(); j++) {
                if (j == 0) {
                    segVideoPaths.add(genFadeVideo(new File(interval.get(j))));
                } else {
                    segVideoPaths.add(interval.get(j));
                }
            }
        }
        return concatByProtocol(segVideoPaths, targetVideo);
    }

    @Override
    public boolean mergeMultiWithFadeV2(List<List<String>> intervals, File targetVideo, String title) {
        // 单独一个不处理
        if (intervals.size() == 1) {
            return concatByDemuxer(intervals.get(0), targetVideo);
        }
        File tmpSaveDir = new File(targetVideo.getParent(), "tmp");
        if (!tmpSaveDir.exists()) {
            tmpSaveDir.mkdir();
        }

        List<String> mergedPaths = Lists.newArrayList();
        for (int i = 0; i < intervals.size(); i++) {
            File tmpFile = new File((tmpSaveDir), "tmp-" + (i + 1) + ".ts");
            boolean success = concatByDemuxer(intervals.get(i), tmpFile);
            if (success) {
                if (i == 0) {
                    mergedPaths.add(genTitleVideo(tmpFile, title));
                } else {
                    mergedPaths.add(genFadeVideo(tmpFile));
                }
            }
        }

        boolean success = concatByDemuxer(mergedPaths, targetVideo);
        if (success) {
            FileUtils.deleteQuietly(tmpSaveDir);
        }
        return success;
    }

    private String genTitleVideo(File tmpFile, String title) {
        String tmpDir = tmpFile.getParent();
        File thumnailFile = new File(tmpDir, "h-thumnail.png");
        File titledSeg = new File(tmpDir, FileNameUtil.getPrefix(tmpFile) + "-titled.ts");


        // 创建封面
        VideoSizeDetectCmd detectCmd = new VideoSizeDetectCmd(tmpFile.getAbsolutePath());
        detectCmd.execute(5);
        int width = detectCmd.getWidth();
        int height = detectCmd.getHeight();
        int fontSize = Math.max((int) height / 13, 20);
        PictureFileUtil.createTextWithVeil(title, width, height, fontSize, thumnailFile);

        // 合并封面和视频
        String fadedPath = titledSeg.getAbsolutePath();
        String cmd = "ffmpeg -y -loglevel error -i " + tmpFile.getAbsolutePath() + " -i " + thumnailFile.getAbsolutePath() +
                " -filter_complex \"[0][1]overlay=enable='between(t,0,1)':format=auto\" -c:v libx264 -crf 24 -preset superfast -c:a aac " + fadedPath;
        FFmpegProcessCmd processCmd = new FFmpegProcessCmd(cmd);
        processCmd.execute(3 * 3600L);
        if (processCmd.isEndNormal()) {
            log.info("add title success, path: {}, title: {}", fadedPath, title);
            return fadedPath;
        } else {
            log.info("add title fail, will use origin video, path: {}", fadedPath);
            return tmpFile.getAbsolutePath();
        }
    }

    /**
     * @param oldVideoFile
     * @return
     */
    private String genFadeVideo(File oldVideoFile) {
        File fadedSeg = new File(oldVideoFile.getParent(), FileNameUtil.getPrefix(oldVideoFile) + "-fade.ts");
        String fadedPath = fadedSeg.getAbsolutePath();
        String cmd = "ffmpeg -y -loglevel error -i " + oldVideoFile.getAbsolutePath() + " -vf fade=t=in:st=0:d=" + FADE_DURATION + " -c:v libx264 -crf 24 -preset superfast -c:a aac " + fadedPath;
        FFmpegProcessCmd processCmd = new FFmpegProcessCmd(cmd);
        processCmd.execute(3 * 3600L);
        if (processCmd.isEndNormal()) {
            log.info("do fade success, path: {}", fadedPath);
            return fadedPath;
        } else {
            log.info("do fade fail, will use origin video, path: {}", fadedPath);
            return oldVideoFile.getAbsolutePath();
        }
    }

    public static void main(String[] args) {
        VideoMergeServiceImpl videoMergeService = new VideoMergeServiceImpl();
        File targetFile = new File("G:\\stream_record\\download\\TheShy\\2024-01-31-03-31-43\\highlight.mp4");
        List<List<String>> intervals = Lists.newArrayList(
                Lists.newArrayList(
                        "G:\\stream_record\\download\\TheShy\\2024-01-31-03-31-43\\seg-458.ts",
                        "G:\\stream_record\\download\\TheShy\\2024-01-31-03-31-43\\seg-459.ts",
                        "G:\\stream_record\\download\\TheShy\\2024-01-31-03-31-43\\seg-460.ts",
                        "G:\\stream_record\\download\\TheShy\\2024-01-31-03-31-43\\seg-461.ts"
                ),
                Lists.newArrayList(
                        "G:\\stream_record\\download\\TheShy\\2024-01-31-03-31-43\\seg-627.ts",
                        "G:\\stream_record\\download\\TheShy\\2024-01-31-03-31-43\\seg-628.ts",
                        "G:\\stream_record\\download\\TheShy\\2024-01-31-03-31-43\\seg-629.ts",
                        "G:\\stream_record\\download\\TheShy\\2024-01-31-03-31-43\\seg-630.ts"
                )
        );
        videoMergeService.mergeMultiWithFadeV2(intervals, targetFile, "Thesy精彩直播\n2929-98-1晚上");
    }
}

