package com.sh.engine.processor.recorder;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.sh.config.utils.VideoFileUtil;
import com.sh.engine.model.ffmpeg.FfmpegRecordCmd;
import com.sh.engine.model.ffmpeg.YtDlpVAMerProcessCmd;
import com.sh.engine.model.ffmpeg.YtDlpVASepProcessCmd;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 采用音频和视频流进行录制
 *
 * @Author caiwen
 * @Date 2025 06 07 17 02
 **/
@Slf4j
public class VodM3u8Recorder extends Recorder {
    private String vodUrl;

    public VodM3u8Recorder(Date regDate, Integer streamChannelType, String vodUrl) {
        this(regDate, streamChannelType, vodUrl, Maps.newHashMap());
    }

    public VodM3u8Recorder(Date regDate, Integer streamChannelType, String vodUrl, Map<String, String> extra) {
        super(regDate, streamChannelType, extra);
        this.vodUrl = vodUrl;
    }

    @Override
    public void doRecord(String savePath) {
        boolean videoAudioSep = true;

        // 音频分开的检测
        List<String> audioM3u8Urls = Lists.newArrayList();
        List<String> videoM3u8Urls = Lists.newArrayList();
        try {
            YtDlpVASepProcessCmd sepCmd = new YtDlpVASepProcessCmd(vodUrl, streamChannelType);
            sepCmd.execute(20);
            audioM3u8Urls = sepCmd.getAudioM3u8Urls();
            videoM3u8Urls = sepCmd.getVideoM3u8Urls();
        } catch (Exception e) {
            log.error("yt-dlp audio-video-separated detect error, try audio-video-merged detect, vodUrl: {}", vodUrl);
            videoAudioSep = false;
        }

        // 音频合并检测
        List<String> mergeUrls = Lists.newArrayList();
        if (!videoAudioSep) {
            YtDlpVAMerProcessCmd mergeCmd = new YtDlpVAMerProcessCmd(vodUrl, streamChannelType);
            mergeCmd.execute(20);
            mergeUrls = mergeCmd.getMergeUrls();
        }

        if (videoAudioSep) {
            doVASepRecord(savePath, audioM3u8Urls, videoM3u8Urls);
        } else {
            doVAMerRecord(savePath, mergeUrls);
        }
    }


    private void doVASepRecord(String savePath, List<String> audioM3u8Urls, List<String> videoM3u8Urls) {
        if (CollectionUtils.isEmpty(audioM3u8Urls) || CollectionUtils.isEmpty(videoM3u8Urls) || audioM3u8Urls.size() != videoM3u8Urls.size()) {
            log.error("no audio or video m3u8, will skip, vodUrl: {}", vodUrl);
            return;
        }

        int totalSize = videoM3u8Urls.size();
        for (int i = 0; i < videoM3u8Urls.size(); i++) {
            log.info("{}th vod record start, process: {}/{}", i + 1, i + 1, totalSize);
            FfmpegRecordCmd rfCmd = new FfmpegRecordCmd(buildSepRecordCmd(savePath, audioM3u8Urls.get(i), videoM3u8Urls.get(i)));
            // 执行录制，长时间
            rfCmd.execute(24 * 3600L);

            if (rfCmd.isExitNormal()) {
                log.info("vod stream record end, savePath: {}", savePath);
            } else {
                log.error("vod stream record fail, savePath: {}", savePath);
            }
        }
    }


    private void doVAMerRecord(String savePath, List<String> mergeUrls) {
        if (CollectionUtils.isEmpty(mergeUrls)) {
            log.error("no video m3u8, will skip, vodUrl: {}", vodUrl);
            return;
        }

        int totalSize = mergeUrls.size();
        for (int i = 0; i < mergeUrls.size(); i++) {
            log.info("{}th vod record start, process: {}/{}", i + 1, i + 1, totalSize);
            FfmpegRecordCmd rfCmd = new FfmpegRecordCmd(buildMergeRecordCmd(savePath, mergeUrls.get(i)));
            // 执行录制，长时间
            rfCmd.execute(24 * 3600L);

            if (rfCmd.isExitNormal()) {
                log.info("vod stream record end, savePath: {}", savePath);
            } else {
                log.error("vod stream record fail, savePath: {}", savePath);
            }
        }
    }

    private String buildSepRecordCmd(String savePath, String audioM3u8Url, String videoM3u8Url) {
        // 计算分端视频开始index(默认从1开始)
        Integer segStartIndex = FileUtils.listFiles(new File(savePath), new String[]{"ts"}, false)
                .stream()
                .map(file -> VideoFileUtil.genIndex(file.getName()))
                .max(Integer::compare)
                .orElse(0) + 1;
        log.info("vod stream record start, savePath: {}, segStartIndex: {}", savePath, segStartIndex);

        File segFile = new File(savePath, VideoFileUtil.SEG_FILE_NAME);
        List<String> commands = Lists.newArrayList(
                "ffmpeg",
                "-y",
                "-loglevel error",
                "-hide_banner",
                "-i", "\"" + videoM3u8Url + "\"",
                "-i", "\"" + audioM3u8Url + "\"",
                "-bufsize 50000k",
                "-c:v copy -c:a copy",
                "-map 0:v -map 1:a",
                "-f segment",
                "-segment_time 4",
                "-segment_start_number", String.valueOf(segStartIndex),
                "-segment_format mpegts",
                "\"" + segFile.getAbsolutePath() + "\""
        );
        return StringUtils.join(commands, " ");
    }


    private String buildMergeRecordCmd(String savePath, String videoM3u8Url) {
        // 计算分端视频开始index(默认从1开始)
        Integer segStartIndex = FileUtils.listFiles(new File(savePath), new String[]{"ts"}, false)
                .stream()
                .map(file -> VideoFileUtil.genIndex(file.getName()))
                .max(Integer::compare)
                .orElse(0) + 1;
        log.info("vod stream record start, savePath: {}, segStartIndex: {}", savePath, segStartIndex);

        File segFile = new File(savePath, VideoFileUtil.SEG_FILE_NAME);
        List<String> commands = Lists.newArrayList(
                "ffmpeg",
                "-y",
                "-loglevel error",
                "-hide_banner",
                "-i", "\"" + videoM3u8Url + "\"",
                "-bufsize 50000k",
                "-c:v copy -c:a copy",
                "-map 0:v -map 0:a",
                "-f segment",
                "-segment_time 4",
                "-segment_start_number", String.valueOf(segStartIndex),
                "-segment_format mpegts",
                "\"" + segFile.getAbsolutePath() + "\""
        );
        return StringUtils.join(commands, " ");
    }
}
