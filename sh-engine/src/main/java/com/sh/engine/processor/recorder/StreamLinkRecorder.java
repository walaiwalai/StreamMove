package com.sh.engine.processor.recorder;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.sh.config.exception.ErrorEnum;
import com.sh.config.exception.StreamerRecordException;
import com.sh.config.manager.ConfigFetcher;
import com.sh.config.model.config.StreamerConfig;
import com.sh.config.utils.VideoFileUtil;
import com.sh.engine.base.StreamerInfoHolder;
import com.sh.engine.constant.RecordConstant;
import com.sh.engine.constant.StreamChannelTypeEnum;
import com.sh.engine.model.ffmpeg.FfmpegRecordCmd;
import com.sh.engine.model.ffmpeg.StreamLinkCheckCmd;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.util.Date;
import java.util.List;
import java.util.Objects;

/**
 * streamlink录像机
 *
 * @Author caiwen
 * @Date 2024 09 28 10 12
 **/
@Slf4j
public class StreamLinkRecorder extends Recorder {
    private String url;

    public StreamLinkRecorder(Date regDate, Integer streamChannelType, String url) {
        super(regDate, streamChannelType, Maps.newHashMap());
        this.url = url;
    }

    @Override
    public void doRecord(String savePath) {
        StreamerConfig streamerConfig = ConfigFetcher.getStreamerInfoByName(StreamerInfoHolder.getCurStreamerName());
        if (BooleanUtils.isTrue(streamerConfig.isRecordWhenOnline())) {
            // 录制在线视频
            recordOnline(savePath);
        } else {
            // 录制回放
            recordReplay(savePath);
        }
    }

    private void recordReplay(String savePath) {
        // 如果是在线的录制，再次检查是否在线
        StreamLinkCheckCmd checkCmd = new StreamLinkCheckCmd(this.url);
        checkCmd.execute(40);
        String bestResolution = checkCmd.getBestResolution();
        if (!StringUtils.contains(bestResolution, "720") && !StringUtils.contains(bestResolution, "1080")) {
            log.error("Resolution is too low {}, stopping recording...", bestResolution);
            FileUtils.deleteQuietly(new File(savePath));
            throw new StreamerRecordException(ErrorEnum.RECORD_BAD_QUALITY);
        }

        log.info("Resolution is OK {}, start recording...", bestResolution);
        FfmpegRecordCmd rfCmd = new FfmpegRecordCmd(buildCmd(savePath));

        // 长时间录播（阻塞）
        rfCmd.execute(24 * 3600L);
        if (!rfCmd.isExitNormal()) {
            log.error("replay stream record fail, savePath: {}", savePath);
            throw new StreamerRecordException(ErrorEnum.FFMPEG_EXECUTE_ERROR);
        }

        log.info("replay stream record end, savePath: {}", savePath);
    }


    private void recordOnline(String savePath) {
        int totalCnt = RecordConstant.RECORD_RETRY_CNT;
        for (int i = 0; i < totalCnt; i++) {
            // 如果是在线的录制，再次检查是否在线
            StreamLinkCheckCmd checkCmd = new StreamLinkCheckCmd(this.url);
            checkCmd.execute(40);
            if (!checkCmd.isStreamOnline()) {
                try {
                    // 睡40s防止重试太快
                    Thread.sleep(40 * 1000);
                } catch (InterruptedException e) {
                }
                log.info("living stream offline confirm, savePath: {}, retry: {}/{}", savePath, i + 1, totalCnt);
                continue;
            }

            log.info("living stream record begin, savePath: {}, retry: {}/{}", savePath, i + 1, totalCnt);
            FfmpegRecordCmd rfCmd = new FfmpegRecordCmd(buildCmd(savePath));
            // 执行录制，长时间
            rfCmd.execute(24 * 3600L);

            if (!rfCmd.isExitNormal()) {
                log.error("living stream record fail, savePath: {}", savePath);
//                throw new StreamerRecordException(ErrorEnum.FFMPEG_EXECUTE_ERROR);
            }
        }
        log.info("living stream record end, savePath: {}", savePath);
    }


    private String buildCmd(String savePath) {
        StreamerConfig streamerConfig = ConfigFetcher.getStreamerInfoByName(StreamerInfoHolder.getCurStreamerName());

        List<String> extraArgs = buildStreamlinkChannelParams();

        // 计算分端视频开始index(默认从1开始)
        Integer segStartIndex = FileUtils.listFiles(new File(savePath), new String[]{"ts"}, false)
                .stream()
                .map(file -> VideoFileUtil.genIndex(file.getName()))
                .max(Integer::compare)
                .orElse(1);

        File segFile = new File(savePath, VideoFileUtil.SEG_FILE_NAME);
        List<String> commands = Lists.newArrayList(
                "streamlink", StringUtils.join(extraArgs, " "),
                "--stream-segment-threads 3",
                "--retry-streams 3",
                "--retry-open 3",
                url, "best",
                "--stdout", "|",
                "ffmpeg",
                "-y",
                "-v verbose",
                "-loglevel error",
                "-hide_banner",
                "-i -",
                BooleanUtils.isTrue(streamerConfig.isOnlyAudio()) ? "-vn" : "",
                "-bufsize 10000k",
                BooleanUtils.isTrue(streamerConfig.isOnlyAudio()) ? "-c:a copy -c:s mov_text" : "-c:v copy -c:a copy -c:s mov_text",
                Objects.equals(StreamChannelTypeEnum.TWITCH.getType(), streamChannelType) ? "-map 0:v -map 0:a" : "-map 0",
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

//    private void doSaveMetaData() {
//        File metadataFile = new File(savePath, "metadata.json");
//        List<String> commands = Lists.newArrayList(
//                "streamlink", "--json",
//                url,
//                "|", "jq .metadata >", metadataFile.getAbsolutePath()
//        );
//        String cmd = StringUtils.join(commands, " ");
//
//        Integer resCode = CommandUtil.cmdExec(new FfmpegCmd(cmd));
//        if (resCode == 0) {
//            log.info("load metadata success, savePath: {}, code: {}", savePath, resCode);
//        } else {
//            log.error("load metadata fail, savePath: {}, code: {}", savePath, resCode);
//        }
//    }
}
