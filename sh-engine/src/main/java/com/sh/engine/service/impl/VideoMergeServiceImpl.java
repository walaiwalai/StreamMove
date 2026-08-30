package com.sh.engine.service.impl;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.file.FileNameUtil;
import com.google.common.collect.Lists;
import com.sh.config.exception.ErrorEnum;
import com.sh.config.exception.StreamerRecordException;
import com.sh.config.utils.EnvUtil;
import com.sh.config.utils.PictureFileUtil;
import com.sh.config.utils.VideoFileUtil;
import com.sh.engine.model.ffmpeg.FFmpegProcessCmd;
import com.sh.engine.model.ffmpeg.Ts2Mp4ProcessCmd;
import com.sh.engine.model.ffmpeg.VideoSizeDetectCmd;
import com.sh.engine.model.highlight.VideoInterval;
import com.sh.engine.model.highlight.core.HighlightMask;
import com.sh.engine.model.highlight.core.HighlightMaskPlan;
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
    private static final int MAX_BLUR_RADIUS = 20;
    private static final double MASK_DARKEN_OPACITY = 0.18;

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
        return mergeWithCover(
                intervals, targetVideo, title, false, HighlightMaskPlan.empty());
    }

    @Override
    public boolean mergeWithCover(List<VideoInterval> intervals,
                                  File targetVideo,
                                  String title,
                                  HighlightMaskPlan maskPlan) {
        return mergeWithCover(intervals, targetVideo, title, false, maskPlan);
    }

    @Override
    public boolean mergeVerticalWithCover(List<VideoInterval> intervals, File targetVideo, String title) {
        return mergeWithCover(
                intervals, targetVideo, title, true, HighlightMaskPlan.empty());
    }

    @Override
    public boolean mergeVerticalWithCover(List<VideoInterval> intervals,
                                          File targetVideo,
                                          String title,
                                          HighlightMaskPlan maskPlan) {
        return mergeWithCover(intervals, targetVideo, title, true, maskPlan);
    }

    /**
     * 统一执行横版或竖版高光合成，蒙层始终在版式变换之前作用于源画面。
     */
    private boolean mergeWithCover(List<VideoInterval> intervals,
                                   File targetVideo,
                                   String title,
                                   boolean verticalLayout,
                                   HighlightMaskPlan maskPlan) {
        if (!validateHighlightIntervals(intervals, targetVideo)) {
            return false;
        }
        File tmpSaveDir = new File(targetVideo.getParent(), "tmp-h");
        if (!tmpSaveDir.isDirectory() && !tmpSaveDir.mkdirs()) {
            throw new StreamerRecordException(
                    ErrorEnum.HIGHLIGHT_ANALYSIS_ERROR,
                    "cannot create highlight merge directory: " + tmpSaveDir.getAbsolutePath());
        }
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
        StringBuilder command = buildHighlightInputCommand(intervals, thumbnailFile);
        String filter = buildHighlightFilter(
                intervals, maskPlan, width, height, verticalVideoFilter);
        command.append(" -filter_complex \"").append(filter).append("\"")
                .append(" -map \"[v_out]\" -map \"[a_out]\"")
                .append(" -c:v libx264 -preset superfast -crf 23 -pix_fmt yuv420p")
                .append(" -c:a aac -movflags +faststart \"")
                .append(targetVideo.getAbsolutePath()).append("\"");
        return executeHighlightMerge(command.toString(), thumbnailFile, tmpSaveDir);
    }

    /**
     * 校验待合并区间，非法区间不执行耗时转码。
     */
    private boolean validateHighlightIntervals(List<VideoInterval> intervals, File targetVideo) {
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
        return true;
    }

    /**
     * 为每个区间生成独立输入，并把封面图片追加为最后一个输入。
     */
    private StringBuilder buildHighlightInputCommand(List<VideoInterval> intervals,
                                                      File thumbnailFile) {
        StringBuilder command = new StringBuilder("ffmpeg -y -loglevel error");
        for (VideoInterval interval : intervals) {
            double duration = interval.getSecondToVideoEnd() - interval.getSecondFromVideoStart();
            command.append(String.format(Locale.ROOT,
                    " -ss %.3f -t %.3f -i \"%s\"",
                    interval.getSecondFromVideoStart(), duration,
                    interval.getFromVideo().getAbsolutePath()));
        }
        return command.append(" -i \"").append(thumbnailFile.getAbsolutePath()).append("\"");
    }

    /**
     * 构建蒙层、版式、淡入淡出、封面和最终拼接组成的完整滤镜图。
     */
    private String buildHighlightFilter(List<VideoInterval> intervals,
                                        HighlightMaskPlan maskPlan,
                                        int width,
                                        int height,
                                        String verticalVideoFilter) {
        StringBuilder filter = new StringBuilder();
        HighlightVideoFilterSpec filterSpec = new HighlightVideoFilterSpec(
                maskPlan, width, height, verticalVideoFilter);
        for (int index = 0; index < intervals.size(); index++) {
            VideoInterval interval = intervals.get(index);
            double duration = interval.getSecondToVideoEnd() - interval.getSecondFromVideoStart();
            double fadeDuration = Math.min(0.5, duration / 2.0);
            double fadeOutStart = Math.max(0.0, duration - fadeDuration);
            appendHighlightVideoFilter(
                    filter, index, filterSpec, fadeDuration, fadeOutStart);
            appendHighlightAudioFilter(filter, index, fadeDuration, fadeOutStart);
        }
        appendHighlightConcatFilter(filter, intervals.size());
        return filter.toString();
    }

    /**
     * 为单个区间添加广告蒙层、可选竖版变换和视频淡入淡出。
     */
    private void appendHighlightVideoFilter(StringBuilder filter,
                                            int inputIndex,
                                            HighlightVideoFilterSpec filterSpec,
                                            double fadeDuration,
                                            double fadeOutStart) {
        String videoInputLabel = appendMaskFilters(
                filter, inputIndex, filterSpec.maskPlan,
                filterSpec.frameWidth, filterSpec.frameHeight);
        filter.append('[').append(videoInputLabel).append(']');
        if (filterSpec.verticalVideoFilter != null) {
            filter.append(filterSpec.verticalVideoFilter).append(',');
        }
        filter.append("setpts=PTS-STARTPTS,format=yuv420p");
        if (inputIndex > 0) {
            filter.append(String.format(Locale.ROOT, ",fade=t=in:st=0:d=%.3f", fadeDuration));
        }
        String outputLabel = inputIndex == 0 ? "v0_base" : "v" + inputIndex;
        filter.append(String.format(Locale.ROOT, ",fade=t=out:st=%.3f:d=%.3f[%s];",
                fadeOutStart, fadeDuration, outputLabel));
    }

    /**
     * 将单个区间音频统一为 48kHz 双声道并添加淡入淡出。
     */
    private void appendHighlightAudioFilter(StringBuilder filter,
                                            int inputIndex,
                                            double fadeDuration,
                                            double fadeOutStart) {
        filter.append('[').append(inputIndex).append(":a]")
                .append("asetpts=PTS-STARTPTS,aformat=sample_rates=48000:channel_layouts=stereo");
        if (inputIndex > 0) {
            filter.append(String.format(Locale.ROOT, ",afade=t=in:st=0:d=%.3f", fadeDuration));
        }
        filter.append(String.format(Locale.ROOT, ",afade=t=out:st=%.3f:d=%.3f[a%d];",
                fadeOutStart, fadeDuration, inputIndex));
    }

    /**
     * 在首段叠加一秒封面，并按时间顺序拼接全部音视频区间。
     */
    private void appendHighlightConcatFilter(StringBuilder filter, int intervalCount) {
        filter.append("[v0_base][").append(intervalCount)
                .append(":v]overlay=enable='between(t,0,1)':format=auto:eof_action=repeat,format=yuv420p[v0];");
        for (int index = 0; index < intervalCount; index++) {
            filter.append("[v").append(index).append("][a").append(index).append(']');
        }
        filter.append("concat=n=").append(intervalCount).append(":v=1:a=1[v_out][a_out]");
    }

    /**
     * 执行最终转码，并确保临时封面及空工作目录得到清理。
     */
    private boolean executeHighlightMerge(String command,
                                          File thumbnailFile,
                                          File tmpSaveDir) {
        FFmpegProcessCmd processCmd = new FFmpegProcessCmd(command);
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

    private static final class HighlightVideoFilterSpec {
        private final HighlightMaskPlan maskPlan;
        private final int frameWidth;
        private final int frameHeight;
        private final String verticalVideoFilter;

        private HighlightVideoFilterSpec(HighlightMaskPlan maskPlan,
                                         int frameWidth,
                                         int frameHeight,
                                         String verticalVideoFilter) {
            this.maskPlan = maskPlan;
            this.frameWidth = frameWidth;
            this.frameHeight = frameHeight;
            this.verticalVideoFilter = verticalVideoFilter;
        }
    }

    /**
     * 为单个 FFmpeg 视频输入依次叠加局部模糊区域，返回后续滤镜应消费的标签。
     */
    private String appendMaskFilters(StringBuilder filter,
                                     int inputIndex,
                                     HighlightMaskPlan maskPlan,
                                     int frameWidth,
                                     int frameHeight) {
        String sourceLabel = inputIndex + ":v";
        if (maskPlan == null || maskPlan.isEmpty()) {
            return sourceLabel;
        }
        for (int maskIndex = 0; maskIndex < maskPlan.getMasks().size(); maskIndex++) {
            HighlightMask mask = maskPlan.getMasks().get(maskIndex);
            int x = mask.pixelX(frameWidth);
            int y = mask.pixelY(frameHeight);
            int width = mask.pixelWidth(frameWidth);
            int height = mask.pixelHeight(frameHeight);
            int blurRadius = Math.max(2,
                    Math.min(MAX_BLUR_RADIUS, Math.min(width, height) / 4));
            String labelPrefix = "mask" + inputIndex + '_' + maskIndex;
            filter.append('[').append(sourceLabel).append("]split=2[")
                    .append(labelPrefix).append("base][")
                    .append(labelPrefix).append("blur];[")
                    .append(labelPrefix).append("blur]crop=")
                    .append(width).append(':').append(height).append(':')
                    .append(x).append(':').append(y)
                    .append(",boxblur=luma_radius=").append(blurRadius)
                    .append(":luma_power=3:chroma_radius=").append(blurRadius)
                    .append(":chroma_power=3[").append(labelPrefix).append("patch];[")
                    .append(labelPrefix).append("base][")
                    .append(labelPrefix).append("patch]overlay=")
                    .append(x).append(':').append(y).append(":format=auto,")
                    .append("drawbox=x=").append(x).append(":y=").append(y)
                    .append(":w=").append(width).append(":h=").append(height)
                    .append(":color=black@").append(MASK_DARKEN_OPACITY)
                    .append(":t=fill[").append(labelPrefix).append("out];");
            sourceLabel = labelPrefix + "out";
        }
        return sourceLabel;
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

