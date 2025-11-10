package com.sh.engine.model;

import com.google.common.collect.Lists;
import com.sh.config.manager.ConfigFetcher;
import com.sh.config.model.config.StreamerConfig;
import com.sh.config.utils.VideoFileUtil;
import com.sh.engine.constant.RecordConstant;
import com.sh.engine.constant.StreamChannelTypeEnum;
import com.sh.engine.model.ffmpeg.StreamBitrateCmd;
import com.sh.engine.model.ffmpeg.StreamLinkUrlFetchCmd;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 录制命令构建器
 *
 * @Author caiwen
 * @Date 2025 08 08 22 46
 **/
@Slf4j
public class RecordCmdBuilder {
    private final StreamerConfig streamerConfig;
    private final int streamChannelType;
    private final String savePath;

    /**
     * 录制的方式
     */
    private final boolean recordByTime;
    private int mSizePerVideo;
    private int intervalPerVideo;


    /**
     * 录制命令
     */
    private List<String> cmdParams = Lists.newArrayList();

    public RecordCmdBuilder(StreamerConfig config, Integer streamChannelType, String savePath) {
        this.streamerConfig = config;
        this.streamChannelType = streamChannelType;
        this.savePath = savePath;
        this.recordByTime = config.getRecordMode().startsWith("t_");
        if (this.recordByTime) {
            this.intervalPerVideo = Integer.parseInt(config.getRecordMode().substring(2));
        } else {
            this.mSizePerVideo = Integer.parseInt(config.getRecordMode().substring(2));
        }
    }

    public RecordCmdBuilder streamlink(String url, String qualityParam) {
        Integer segStartIndex = FileUtils.listFiles(new File(savePath), new String[]{"ts"}, false)
                .stream()
                .map(file -> VideoFileUtil.genIndex(file.getName()))
                .max(Integer::compare)
                .orElse(0) + 1;
        File segFile = new File(savePath, VideoFileUtil.SEG_FILE_NAME_V2);

        List<String> streamLinkParams = Lists.newArrayList(
                "streamlink", StringUtils.join(buildStreamlinkChannelParams(), " "),
                "--stream-segment-threads 2",
                "--retry-streams 3",
                "--retry-open 3",
                "--hls-segment-attempts 2",
                "--hls-segment-timeout 10",
                "--no-part",
                "--http-header", "\"" + RecordConstant.USER_AGENT + "\"",
                url, qualityParam,
                "--stdout"
        );
        List<String> ffmpegParams;

        ArrayList<String> streamUrls = Lists.newArrayList("-");
        if (recordByTime) {
            ffmpegParams = buildFfmpegByTime(streamUrls, segFile, segStartIndex);
        } else {
            int kbitrate = 0;
            try {
                StreamLinkUrlFetchCmd streamUrlCmd = new StreamLinkUrlFetchCmd(url, qualityParam);
                streamUrlCmd.execute(20);

                StreamBitrateCmd streamBitrateCmd = new StreamBitrateCmd(streamUrlCmd.getStreamUrl());
                streamBitrateCmd.execute(20);

                kbitrate = streamBitrateCmd.getKbitrate();
                this.intervalPerVideo = calIntervalBySize(kbitrate);
            } catch (Exception e) {
                log.error("biterate parse error, will use 3600 per video", e);
                this.intervalPerVideo = 3600;
            }

            // 计算出对应的时间间隔
            log.info("the kbitrate is {}kb/s, mSize: {}M, secondPerVideo: {}s", kbitrate, this.mSizePerVideo, this.intervalPerVideo);
            ffmpegParams = buildFfmpegByTime(streamUrls, segFile, segStartIndex);
        }

        List<String> params = Lists.newArrayList();
        params.addAll(streamLinkParams);
        params.add("|");
        params.addAll(ffmpegParams);

        this.cmdParams = params;
        return this;
    }

    public RecordCmdBuilder streamUrl(String streamUrl) {
        Integer segStartIndex = FileUtils.listFiles(new File(savePath), new String[]{"ts"}, false)
                .stream()
                .map(file -> VideoFileUtil.genIndex(file.getName()))
                .max(Integer::compare)
                .orElse(0) + 1;
        File segFile = new File(savePath, VideoFileUtil.SEG_FILE_NAME_V2);

        ArrayList<String> streamUrls = Lists.newArrayList("\"" + streamUrl + "\"");
        if (recordByTime) {
            this.cmdParams = buildFfmpegByTime(streamUrls, segFile, segStartIndex);
        } else {
            int kbitrate = 0;
            try {
                StreamBitrateCmd streamBitrateCmd = new StreamBitrateCmd(streamUrl);
                streamBitrateCmd.execute(20);

                // 计算出对应的时间间隔
                kbitrate = streamBitrateCmd.getKbitrate();
                if (kbitrate < 0) {
                    throw new RuntimeException("kbitrate < 0");
                }
                this.intervalPerVideo = calIntervalBySize(streamBitrateCmd.getKbitrate());
            } catch (Exception e) {
                log.error("biterate parse error, will use 3600 per video", e);
                this.intervalPerVideo = 3600;
            }

            log.info("the kbitrate is {}kb/s, mSize: {}M, secondPerVideo: {}s", kbitrate, this.mSizePerVideo, this.intervalPerVideo);

            this.cmdParams = buildFfmpegByTime(streamUrls, segFile, segStartIndex);
        }
        return this;
    }

    public RecordCmdBuilder vodM3u8(String audioM3u8Url, String videoM3u8Url) {
        // 计算分端视频开始index(默认从1开始)
        Integer segStartIndex = FileUtils.listFiles(new File(savePath), new String[]{"ts"}, false)
                .stream()
                .map(file -> VideoFileUtil.genIndex(file.getName()))
                .max(Integer::compare)
                .orElse(0) + 1;

        File segFile = new File(savePath, VideoFileUtil.SEG_FILE_NAME_V2);

        ArrayList<String> streamUrls = Lists.newArrayList("\"" + audioM3u8Url + "\"", "\"" + videoM3u8Url + "\"");
        if (recordByTime) {
            this.cmdParams = buildFfmpegByTime(streamUrls, segFile, segStartIndex);
        } else {
            int kbitrate = 0;
            try {
                StreamBitrateCmd streamBitrateCmd = new StreamBitrateCmd(videoM3u8Url);
                streamBitrateCmd.execute(20);

                // 计算出对应的时间间隔
                kbitrate = streamBitrateCmd.getKbitrate();
                this.intervalPerVideo = calIntervalBySize(kbitrate);
            } catch (Exception e) {
                log.error("biterate parse error, will use 3600 per video", e);
                this.intervalPerVideo = 3600;
            }

            log.info("the kbitrate is {}kb/s, mSize: {}M, secondPerVideo: {}s", kbitrate, this.mSizePerVideo, this.intervalPerVideo);

            this.cmdParams = buildFfmpegByTime(streamUrls, segFile, segStartIndex);
        }
        return this;
    }

    public RecordCmdBuilder vodM3u8(String mergeM3u8Url) {
        // 计算分端视频开始index(默认从1开始)
        int segStartIndex = FileUtils.listFiles(new File(savePath), new String[]{"ts"}, false)
                .stream()
                .map(file -> VideoFileUtil.genIndex(file.getName()))
                .max(Integer::compare)
                .orElse(0) + 1;

        File segFile = new File(savePath, VideoFileUtil.SEG_FILE_NAME_V2);

        ArrayList<String> streamUrls = Lists.newArrayList("\"" + mergeM3u8Url + "\"");
        if (recordByTime) {
            this.cmdParams = buildFfmpegByTime(streamUrls, segFile, segStartIndex);
        } else {
            int kbitrate = 0;
            try {
                StreamBitrateCmd streamBitrateCmd = new StreamBitrateCmd(mergeM3u8Url);
                streamBitrateCmd.execute(20);

                // 计算出对应的时间间隔
                kbitrate = streamBitrateCmd.getKbitrate();
                this.intervalPerVideo = calIntervalBySize(kbitrate);
            } catch (Exception e) {
                log.error("biterate parse error, will use 3600 per video", e);
                this.intervalPerVideo = 3600;
            }
            log.info("the kbitrate is {}kb/s, mSize: {}M, secondPerVideo: {}s", kbitrate, this.mSizePerVideo, this.intervalPerVideo);

            this.cmdParams = buildFfmpegByTime(streamUrls, segFile, segStartIndex);
        }
        return this;
    }


    public String build() {
        return StringUtils.join(cmdParams, " ");
    }

    private List<String> buildStreamlinkChannelParams() {
        List<String> extraArgs = Lists.newArrayList();
        // twitch额外参数
        if (Objects.equals(StreamChannelTypeEnum.TWITCH.getType(), streamChannelType)) {
            extraArgs.add("--twitch-disable-ads");
            String authorization = ConfigFetcher.getInitConfig().getTwitchAuthorization();
            if (StringUtils.isNotBlank(authorization)) {
                extraArgs.add(String.format("\"--twitch-api-header=Authorization=%s\"", authorization));
            }
        } else if (Objects.equals(StreamChannelTypeEnum.AFREECA_TV.getType(), streamChannelType)) {
            String soopUserName = ConfigFetcher.getInitConfig().getSoopUserName();
            String soopPassword = ConfigFetcher.getInitConfig().getSoopPassword();
            if (StringUtils.isNotBlank(soopUserName) && StringUtils.isNotBlank(soopPassword)) {
                extraArgs.add(String.format("--soop-username \"%s\"", soopUserName));
                extraArgs.add(String.format("--soop-password \"%s\"", soopPassword));
            }
        }


        return extraArgs;
    }

    private List<String> buildFfmpegByTime(List<String> sourceUrls, File segFile, int segStartIndex) {
        return Lists.newArrayList(
                "ffmpeg",
                "-y",
                "-loglevel error",
                "-hide_banner",
                "-i", StringUtils.join(sourceUrls, " -i "),
                "-rw_timeout", "30000000",
                "-reconnect_streamed", "1",
                "-reconnect_delay_max", "60",
                "-thread_queue_size", "4096",
                "-max_muxing_queue_size", "4096",
                "-analyzeduration", "40000000",
                "-probesize", "20000000",
                "-fflags", "\"+discardcorrupt +igndts +genpts\"",
                "-correct_ts_overflow", "1",
                "-avoid_negative_ts", "1",
                "-rtbufsize", "100M",
                "-bufsize", "50000k",
                StringUtils.join(BooleanUtils.isTrue(streamerConfig.isOnlyAudio()) ? buildOnlyAudioParams() : buildVideoParams(), " "),
                "-f", "segment",
                "-segment_format", "mpegts",
                "-reset_timestamps", "1",
                "-segment_time", String.valueOf(intervalPerVideo),
                "-segment_start_number", String.valueOf(segStartIndex),
                "\"" + segFile.getAbsolutePath() + "\""
        );
    }

    private Integer calIntervalBySize(int kbitrate) {
        double mbPerSecond = (float) kbitrate / 8192.0;
        return (int) Math.ceil(mSizePerVideo / mbPerSecond);
    }

    private List<String> buildOnlyAudioParams() {
        return Lists.newArrayList(
                "-vn",
                "-c:a copy",
                "-map 0:a"
        );
    }

    private List<String> buildVideoParams() {
        return Lists.newArrayList(
                "-c:v copy",
                "-c:a copy",
                "-c:s mov_text",
                "-map 0:v",
                "-map 0:a",
                "-map 0:s?"
        );
    }
}
