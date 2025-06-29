package com.sh.engine.processor.recorder;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.sh.config.utils.VideoFileUtil;
import com.sh.engine.constant.StreamChannelTypeEnum;
import com.sh.engine.model.ffmpeg.FfmpegRecordCmd;
import com.sh.engine.model.ffmpeg.YtDlpStreamFetchProcessCmd;
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
        YtDlpStreamFetchProcessCmd streamFetchCmd = new YtDlpStreamFetchProcessCmd(vodUrl, streamChannelType);
        streamFetchCmd.execute(20);

        List<String> audioM3u8Urls = streamFetchCmd.getAudioM3u8Urls();
        List<String> videoM3u8Urls = streamFetchCmd.getVideoM3u8Urls();
        if (CollectionUtils.isEmpty(audioM3u8Urls) || CollectionUtils.isEmpty(videoM3u8Urls) || audioM3u8Urls.size() != videoM3u8Urls.size()) {
            log.error("no audio or video m3u8, will skip, vodUrl: {}", vodUrl);
            return;
        }

        log.info("vod stream record start, {} video part to download", videoM3u8Urls.size());
        for (int i = 0; i < videoM3u8Urls.size(); i++) {
            FfmpegRecordCmd rfCmd = new FfmpegRecordCmd(buildCmd(savePath, audioM3u8Urls.get(i), videoM3u8Urls.get(i)));
            // 执行录制，长时间
            rfCmd.execute(24 * 3600L);

            if (rfCmd.isExitNormal()) {
                log.info("vod stream record end, savePath: {}", savePath);
            } else {
                log.error("vod stream record fail, savePath: {}", savePath);
            }
        }
    }

    private String buildCmd(String savePath, String audioM3u8Url, String videoM3u8Url) {
        // 计算分端视频开始index(默认从1开始)
        Integer segStartIndex = FileUtils.listFiles(new File(savePath), new String[]{"ts"}, false)
                .stream()
                .map(file -> VideoFileUtil.genIndex(file.getName()))
                .max(Integer::compare)
                .orElse(0) + 1;

        File segFile = new File(savePath, VideoFileUtil.SEG_FILE_NAME);
        List<String> commands = Lists.newArrayList(
                "ffmpeg",
                "-y",
                "-v verbose",
                "-loglevel error",
                "-hide_banner",
                "-i", "\"" + videoM3u8Url + "\"",
                "-i", "\"" + audioM3u8Url + "\"",
                "-bufsize 10000k",
                "-c:v copy -c:a copy -c:s mov_text",
                "-map 0:v -map 1:a",
                "-f segment",
                "-segment_time 4",
                "-segment_start_number", String.valueOf(segStartIndex),
                "-segment_format mp4",
                "-movflags +faststart",
                "-reset_timestamps 1",
                "\"" + segFile.getAbsolutePath() + "\""
        );
        return StringUtils.join(commands, " ");
    }

    public static void main(String[] args) {
        VodM3u8Recorder vodM3u8Recorder = new VodM3u8Recorder(new Date(), StreamChannelTypeEnum.AFREECA_TV.getType(), "https://vod.sooplive.co.kr/player/164464551");
        vodM3u8Recorder.doRecord("G:\\stream_record\\download\\mytest-pc\\2025-04-23-02-02-35");
    }
}
