package com.sh.engine.service.impl;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.file.FileNameUtil;
import com.google.common.collect.Lists;
import com.sh.config.utils.EnvUtil;
import com.sh.config.utils.PictureFileUtil;
import com.sh.config.utils.VideoFileUtil;
import com.sh.engine.model.ffmpeg.FFmpegProcessCmd;
import com.sh.engine.model.ffmpeg.Ts2Mp4ProcessCmd;
import com.sh.engine.model.ffmpeg.VideoSizeDetectCmd;
import com.sh.engine.model.highlight.VideoInterval;
import com.sh.engine.service.VideoMergeService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.SystemUtils;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
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
    private static final int COPY_RETRY = 5;
    private static final int VERTICAL_OUTPUT_WIDTH = 1080;
    private static final int VERTICAL_OUTPUT_HEIGHT = 1920;
    private static final int VERTICAL_GAME_WIDTH_RATIO = 8;
    private static final int VERTICAL_GAME_HEIGHT_RATIO = 9;
    private static final String VERTICAL_BACKGROUND_COLOR = "0x101218";

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
        return mergeWithCover(intervals, targetVideo, title, false);
    }

    @Override
    public boolean mergeVerticalWithCover(List<VideoInterval> intervals, File targetVideo, String title) {
        return mergeWithCover(intervals, targetVideo, title, true);
    }

    private boolean mergeWithCover(List<VideoInterval> intervals,
                                   File targetVideo,
                                   String title,
                                   boolean verticalLayout) {
        if (CollectionUtils.isEmpty(intervals)) {
            log.info("empty video intervals, skip merge, target: {}", targetVideo.getAbsolutePath());
            return false;
        }
        for (VideoInterval interval : intervals) {
            if (interval.getSecondToVideoEnd() <= interval.getSecondFromVideoStart()) {
                log.error("invalid video interval, start: {}, end: {}, video: {}",
                        interval.getSecondFromVideoStart(), interval.getSecondToVideoEnd(),
                        interval.getFromVideo().getAbsolutePath());
                return false;
            }
        }

        File tmpSaveDir = new File(targetVideo.getParent(), "tmp-h");
        tmpSaveDir.mkdirs();
        File thumbnailFile = new File(tmpSaveDir, "h-thumbnail.png");

        VideoSizeDetectCmd detectCmd = new VideoSizeDetectCmd(intervals.get(0).getFromVideo().getAbsolutePath());
        detectCmd.execute(50);
        int width = detectCmd.getWidth();
        int height = detectCmd.getHeight();
        int outputWidth = verticalLayout ? VERTICAL_OUTPUT_WIDTH : width;
        int outputHeight = verticalLayout ? VERTICAL_OUTPUT_HEIGHT : height;
        int fontSize = Math.max(outputHeight / 13, 20);
        PictureFileUtil.createTextWithVeil(title, outputWidth, outputHeight, fontSize, thumbnailFile);

        String verticalVideoFilter = verticalLayout ? buildVerticalVideoFilter(width, height) : null;

        StringBuilder command = new StringBuilder("ffmpeg -y -loglevel error");
        for (VideoInterval interval : intervals) {
            double duration = interval.getSecondToVideoEnd() - interval.getSecondFromVideoStart();
            command.append(String.format(Locale.ROOT,
                    " -ss %.3f -t %.3f -i \"%s\"",
                    interval.getSecondFromVideoStart(), duration,
                    interval.getFromVideo().getAbsolutePath()));
        }
        command.append(" -i \"").append(thumbnailFile.getAbsolutePath()).append("\"");

        StringBuilder filter = new StringBuilder();
        for (int i = 0; i < intervals.size(); i++) {
            VideoInterval interval = intervals.get(i);
            double duration = interval.getSecondToVideoEnd() - interval.getSecondFromVideoStart();
            double fadeDuration = Math.min(0.5, duration / 2.0);
            double fadeOutStart = Math.max(0.0, duration - fadeDuration);

            filter.append('[').append(i).append(":v]");
            if (verticalLayout) {
                filter.append(verticalVideoFilter).append(',');
            }
            filter.append("setpts=PTS-STARTPTS,format=yuv420p");
            if (i > 0) {
                filter.append(String.format(Locale.ROOT, ",fade=t=in:st=0:d=%.3f", fadeDuration));
            }
            String videoOutputLabel = i == 0 ? "v0_base" : "v" + i;
            filter.append(String.format(Locale.ROOT, ",fade=t=out:st=%.3f:d=%.3f[%s];",
                    fadeOutStart, fadeDuration, videoOutputLabel));

            filter.append('[').append(i).append(":a]")
                    .append("asetpts=PTS-STARTPTS,aformat=sample_rates=48000:channel_layouts=stereo");
            if (i > 0) {
                filter.append(String.format(Locale.ROOT, ",afade=t=in:st=0:d=%.3f", fadeDuration));
            }
            filter.append(String.format(Locale.ROOT, ",afade=t=out:st=%.3f:d=%.3f[a%d];",
                    fadeOutStart, fadeDuration, i));
        }

        int coverInputIndex = intervals.size();
        filter.append("[v0_base][").append(coverInputIndex)
                .append(":v]overlay=enable='between(t,0,1)':format=auto:eof_action=repeat,format=yuv420p[v0];");
        for (int i = 0; i < intervals.size(); i++) {
            filter.append("[v").append(i).append("][a").append(i).append(']');
        }
        filter.append("concat=n=").append(intervals.size()).append(":v=1:a=1[v_out][a_out]");

        command.append(" -filter_complex \"").append(filter).append("\"")
                .append(" -map \"[v_out]\" -map \"[a_out]\"")
                .append(" -c:v libx264 -preset superfast -crf 23 -pix_fmt yuv420p")
                .append(" -c:a aac -movflags +faststart \"")
                .append(targetVideo.getAbsolutePath()).append("\"");

        FFmpegProcessCmd processCmd = new FFmpegProcessCmd(command.toString());
        try {
            processCmd.execute(3 * 3600L);
            return processCmd.isEndNormal();
        } finally {
            FileUtils.deleteQuietly(thumbnailFile);
            String[] remainingFiles = tmpSaveDir.list();
            if (remainingFiles != null && remainingFiles.length == 0) {
                FileUtils.deleteQuietly(tmpSaveDir);
            }
        }
    }

    static String buildVerticalVideoFilter(int sourceWidth, int sourceHeight) {
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            throw new IllegalArgumentException("invalid source video size: " + sourceWidth + "x" + sourceHeight);
        }

        int cropWidth = sourceHeight * VERTICAL_GAME_WIDTH_RATIO / VERTICAL_GAME_HEIGHT_RATIO;
        cropWidth = Math.min(sourceWidth, cropWidth);
        cropWidth -= cropWidth % 2;
        if (cropWidth <= 0) {
            throw new IllegalArgumentException("invalid vertical crop width: " + cropWidth);
        }

        int cropX = Math.max(0, (sourceWidth - cropWidth) / 2);
        cropX -= cropX % 2;
        if ((long) sourceHeight * VERTICAL_OUTPUT_WIDTH > (long) cropWidth * VERTICAL_OUTPUT_HEIGHT) {
            throw new IllegalArgumentException(
                    "source video is too narrow for vertical layout: " + sourceWidth + "x" + sourceHeight);
        }

        return String.format(Locale.ROOT,
                "crop=%d:%d:%d:0,scale=%d:-2:flags=fast_bilinear," +
                        "pad=%d:%d:0:(oh-ih)/2:color=%s",
                cropWidth, sourceHeight, cropX,
                VERTICAL_OUTPUT_WIDTH, VERTICAL_OUTPUT_WIDTH, VERTICAL_OUTPUT_HEIGHT,
                VERTICAL_BACKGROUND_COLOR);
    }

    @Override
    public boolean ts2Mp4(File fromVideo) {
        if (EnvUtil.isStorageMounted()) {
            // 如果挂载下载路径，直接处理会报错
            File tmpDir = VideoFileUtil.getAmountedTmpDir();
            File toMp4File = new File(tmpDir, FileNameUtil.getPrefix(fromVideo) + ".mp4");

            Ts2Mp4ProcessCmd ts2Mp4ProcessCmd = new Ts2Mp4ProcessCmd(fromVideo, toMp4File);
            ts2Mp4ProcessCmd.execute(2 * 3600L);

            // copy文件
            File targetFile = new File(fromVideo.getParent(), FileNameUtil.getPrefix(fromVideo) + ".mp4");
            boolean copySuccess = false;
            for (int i = 0; i < COPY_RETRY; i++) {
                if (copySuccess) {
                    break;
                }
                try {
                    // 清空目标文件
                    FileUtils.deleteQuietly(targetFile);
                    // 执行拷贝
                    FileUtils.copyFile(toMp4File, targetFile);
                    copySuccess = true;
                } catch (IOException e) {
                    log.error("copy file fail, from: {}, to: {}, retry: {}/{}", toMp4File.getAbsolutePath(), targetFile.getAbsolutePath(), i + 1, COPY_RETRY, e);
                }
            }

            // 删除临时文件
            FileUtils.deleteQuietly(tmpDir);
            if (!copySuccess) {
                log.error("fuck! copy file fail, from: {}, to: {}", toMp4File.getAbsolutePath(), targetFile.getAbsolutePath());
            }
            return copySuccess;
        } else {
            File toMp4File = new File(fromVideo.getParent(), FileNameUtil.getPrefix(fromVideo) + ".mp4");
            Ts2Mp4ProcessCmd ts2Mp4ProcessCmd = new Ts2Mp4ProcessCmd(fromVideo, toMp4File);
            ts2Mp4ProcessCmd.execute(2 * 3600L);
            return true;
        }
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

