package com.sh.engine.processor.recorder.stream;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.sh.config.manager.ConfigFetcher;
import com.sh.config.model.config.StreamerConfig;
import com.sh.engine.model.RecordCmdBuilder;
import com.sh.engine.model.StreamerInfoHolder;
import com.sh.engine.model.ffmpeg.FfmpegRecordCmd;
import com.sh.engine.model.ffmpeg.YtDlpVAMerProcessCmd;
import com.sh.engine.model.ffmpeg.YtDlpVASepProcessCmd;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;

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
public class YtdlpStreamRecorder extends StreamRecorder {
    private String vodUrl;

    public YtdlpStreamRecorder( Date regDate, Integer streamChannelType, String vodUrl) {
        this(regDate, streamChannelType, vodUrl, Maps.newHashMap());
    }

    public YtdlpStreamRecorder( Date regDate, Integer streamChannelType, String vodUrl, Map<String, String> extra) {
        super(regDate, streamChannelType, extra);
        this.vodUrl = vodUrl;
    }

    @Override
    public void start(String savePath) {
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
        StreamerConfig streamerConfig = ConfigFetcher.getStreamerInfoByName(StreamerInfoHolder.getCurStreamerName());
        RecordCmdBuilder builder = new RecordCmdBuilder(streamerConfig, this.streamChannelType, savePath);
        int totalSize = videoM3u8Urls.size();
        for (int i = 0; i < videoM3u8Urls.size(); i++) {
            log.info("{}th vod record start, process: {}/{}", i + 1, i + 1, totalSize);

            String cmd = builder.vodM3u8(audioM3u8Urls.get(i), videoM3u8Urls.get(i)).build();
            FfmpegRecordCmd rfCmd = new FfmpegRecordCmd(cmd);
            // 执行录制，长时间
            rfCmd.execute(24 * 3600L);

            if (rfCmd.isNormalExit()) {
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

        StreamerConfig streamerConfig = ConfigFetcher.getStreamerInfoByName(StreamerInfoHolder.getCurStreamerName());
        RecordCmdBuilder builder = new RecordCmdBuilder(streamerConfig, this.streamChannelType, savePath);
        int totalSize = mergeUrls.size();
        for (int i = 0; i < mergeUrls.size(); i++) {
            log.info("{}th vod record start, process: {}/{}", i + 1, i + 1, totalSize);
            String cmd = builder.vodM3u8(mergeUrls.get(i)).build();

            FfmpegRecordCmd rfCmd = new FfmpegRecordCmd(cmd);
            // 执行录制，长时间
            rfCmd.execute(24 * 3600L);

            if (rfCmd.isNormalExit()) {
                log.info("vod stream record end, savePath: {}", savePath);
            } else {
                log.error("vod stream record fail, savePath: {}", savePath);
            }
        }
    }
}
