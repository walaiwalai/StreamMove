package com.sh.engine.service.process;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.file.FileNameUtil;
import com.google.common.collect.Lists;
import com.sh.config.utils.PictureFileUtil;
import com.sh.engine.model.ffmpeg.FFmpegProcessCmd;
import com.sh.engine.model.ffmpeg.Ts2Mp4ProcessCmd;
import com.sh.engine.model.ffmpeg.VideoSizeDetectCmd;
import com.sh.engine.model.video.VideoInterval;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.SystemUtils;
import org.springframework.stereotype.Component;

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
    /**
     * 淡出时间（s）
     */
    private static final int FADE_DURATION = 1;

    @Override
    public boolean concatWithSameVideo(List<String> mergedFps, File targetVideo) {
        // 1. 写mergeList
        File mergeListFile = saveMergeFileList(mergedFps, targetVideo);

        // 2. 使用FFmpeg合并视频
        String targetPath = targetVideo.getAbsolutePath();
        String command = "ffmpeg -y -loglevel error -f concat -safe 0 -i " + mergeListFile.getAbsolutePath() +
                " -c:v copy -c:a copy " + targetPath;
        FFmpegProcessCmd processCmd = new FFmpegProcessCmd(command);
        processCmd.execute(3 * 3600L);
        return processCmd.isEndNormal();
    }

    @Override
    public boolean concatDiffVideos(List<String> mergedFps, File targetVideo) {
        List<String> processFps = Lists.newArrayList();
        for (String fp : mergedFps) {
            File processedFile = genProcessVideo(fp, targetVideo);
            if (processedFile != null) {
                processFps.add(processedFile.getAbsolutePath());
            }
        }

        if (CollectionUtils.isEmpty(processFps)) {
            return false;
        }

        // 1. 写mergeList
        File mergeListFile = saveMergeFileList(processFps, targetVideo);

        // 2. 执行合并
        String command = "ffmpeg -y -loglevel error -f concat -safe 0 -i " + mergeListFile.getAbsolutePath() +
                " -c copy -c:a aac " + targetVideo.getAbsolutePath();
        FFmpegProcessCmd processCmd = new FFmpegProcessCmd(command);
        processCmd.execute(3 * 3600L);

        // 3. 删除处理的中间文件
        for (String fp : processFps) {
            FileUtil.del(fp);
        }
        return processCmd.isEndNormal();
    }

    @Override
    public boolean mergeWithCover(List<VideoInterval> intervals, File targetVideo, String title) {
        File tmpSaveDir = new File(targetVideo.getParent(), "tmp-h");
        tmpSaveDir.mkdirs();

        List<String> mergedPaths = Lists.newArrayList();
        for (int i = 0; i < intervals.size(); i++) {
            if (i == 0) {
                mergedPaths.add(genTitleVideo(intervals.get(i), tmpSaveDir, title).getAbsolutePath());
            } else {
                mergedPaths.add(genFadeVideo(intervals.get(i), tmpSaveDir).getAbsolutePath());
            }
        }

        return concatWithSameVideo(mergedPaths, targetVideo);
    }

    @Override
    public boolean ts2Mp4(File fromVideo) {
        Ts2Mp4ProcessCmd ts2Mp4ProcessCmd = new Ts2Mp4ProcessCmd(fromVideo);
        ts2Mp4ProcessCmd.execute(4 * 3600L);
        return true;
    }

    private File saveMergeFileList(List<String> mergedFileNames, File targetVideo) {
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

        // 写入merge.txt
        try {
            IOUtils.write(StringUtils.join(lines, "\n"), new FileOutputStream(mergeListFile), "utf-8");
        } catch (IOException e) {
            log.error("write merge list file fail, savePath: {}", mergeListFile.getAbsolutePath(), e);
        }

        return mergeListFile;
    }

    private File genProcessVideo(String filePath, File targetVideo) {
        File originalFile = new File(filePath);
        String processFileName = FileNameUtil.getPrefix(originalFile) + "-processd." + FileNameUtil.getSuffix(originalFile);
        File processedFile = new File(targetVideo.getParent(), processFileName);

        String command = "ffmpeg -y -loglevel error -i " + filePath + " -c copy -bsf:v h264_mp4toannexb -f mpegts " + processedFile.getAbsolutePath();
        FFmpegProcessCmd processCmd = new FFmpegProcessCmd(command);
        processCmd.execute(3 * 3600L);
        return processCmd.isEndNormal() ? processedFile : null;
    }

    private File genTitleVideo(VideoInterval videoInterval, File tmpDir, String title) {
        File fromVideo = videoInterval.getFromVideo();
        File thumnailFile = new File(tmpDir, "h-thumnail.png");
        String targetFileName = FileNameUtil.getPrefix(fromVideo) + "-" +
                Math.round(videoInterval.getSecondFromVideoStart()) + "-" +
                Math.round(videoInterval.getSecondToVideoEnd()) + "-titled.ts";
        File titledSeg = new File(tmpDir, targetFileName);

        // 创建封面
        VideoSizeDetectCmd detectCmd = new VideoSizeDetectCmd(fromVideo.getAbsolutePath());
        detectCmd.execute(50);
        int width = detectCmd.getWidth();
        int height = detectCmd.getHeight();
        int fontSize = Math.max((int) height / 13, 20);
        PictureFileUtil.createTextWithVeil(title, width, height, fontSize, thumnailFile);

        // 裁剪并合成封面
        double startTime = videoInterval.getSecondFromVideoStart();
        double endTime = videoInterval.getSecondToVideoEnd();
        // 核心命令
        String cmd = String.format(
                "ffmpeg -y -loglevel error " +
                        "-ss %.1f " +
                        "-i \"%s\" " +
                        "-i \"%s\" " +
                        "-to %.1f " +
                        "-filter_complex " +
                        "\"[0:v]fade=out:st=%.1f:d=0.5[v_cut];[v_cut][1:v]overlay=enable='between(t,0,1)':format=auto[v_out];[0:a]afade=out:st=%.1f:d=0.5[a_out]\" " +
                        "-map \"[v_out]\" -map \"[a_out]\" " +
                        "-c:v libx264 -preset superfast -crf 26 -c:a aac " +
                        "\"%s\"",
                startTime,
                fromVideo.getAbsolutePath(),
                thumnailFile.getAbsolutePath(),
                endTime,
                endTime - 0.5,
                endTime - 0.5,
                titledSeg.getAbsolutePath()
        );
        FFmpegProcessCmd processCmd = new FFmpegProcessCmd(cmd);
        processCmd.execute(3 * 3600L);
        return titledSeg;
    }

    /**
     * 生成带淡出特效的视频
     *
     * @param videoInterval 视频片段
     * @param tmpDir        临时目录
     * @return 生成的视频文件
     */
    private File genFadeVideo(VideoInterval videoInterval, File tmpDir) {
        File fromVideo = videoInterval.getFromVideo();
        String targetFileName = FileNameUtil.getPrefix(fromVideo) + "-" +
                Math.round(videoInterval.getSecondFromVideoStart()) + "-" +
                Math.round(videoInterval.getSecondToVideoEnd()) + "-fade.ts";
        File fadeSeg = new File(tmpDir, targetFileName);

        // 裁剪并加上淡出效果
        double startTime = videoInterval.getSecondFromVideoStart();
        double endTime = videoInterval.getSecondToVideoEnd();

        // 构建FFmpeg命令：裁剪 + 视频淡出效果
        String cmd = String.format(
                "ffmpeg -y -loglevel error " +
                        "-ss %.1f " +
                        "-i \"%s\" " +
                        "-to %.1f " +
                        "-filter_complex " +
                        "\"[0:v]fade=t=in:st=0:d=%.1f[v_out];[0:a]afade=t=in:st=0:d=%.1f[a_out]\" " +
                        "-map \"[v_out]\" -map \"[a_out]\" " +
                        "-c:v libx264 -preset superfast -crf 26 -c:a aac " +
                        "\"%s\"",
                startTime, fromVideo.getAbsolutePath(), endTime,
                1.0, 1.0, fadeSeg.getAbsolutePath()
        );

        FFmpegProcessCmd processCmd = new FFmpegProcessCmd(cmd);
        processCmd.execute(3 * 3600L);
        return fadeSeg;
    }

    public static void main(String[] args) {
        VideoMergeServiceImpl videoMergeService = new VideoMergeServiceImpl();
        File targetFile = new File("G:\\stream_record\\download\\mytest-mac\\2025-08-15-20-59-48\\tmp-h\\final-test.mp4");

        List<VideoInterval> intervals = Lists.newArrayList(
                new VideoInterval(new File("G:\\stream_record\\download\\mytest-mac\\2025-08-15-20-59-48\\seg-04.mp4"), 10.0, 20.0),
                new VideoInterval(new File("G:\\stream_record\\download\\mytest-mac\\2025-08-15-20-59-48\\seg-04.mp4"), 20.0, 40.0)
        );
        videoMergeService.mergeWithCover(intervals, targetFile, "Thesy精彩直播\n2929-98-1晚上");
    }
}

