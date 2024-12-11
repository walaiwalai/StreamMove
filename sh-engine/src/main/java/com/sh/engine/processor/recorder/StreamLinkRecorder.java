package com.sh.engine.processor.recorder;

import com.google.common.collect.Lists;
import com.sh.config.exception.ErrorEnum;
import com.sh.config.exception.StreamerRecordException;
import com.sh.config.manager.ConfigFetcher;
import com.sh.config.model.config.StreamerConfig;
import com.sh.config.utils.VideoFileUtil;
import com.sh.engine.base.StreamerInfoHolder;
import com.sh.engine.constant.RecordConstant;
import com.sh.engine.constant.StreamChannelTypeEnum;
import com.sh.engine.model.ffmpeg.FfmpegRecordCmd;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.util.Date;
import java.util.List;

/**
 * streamlink录像机
 *
 * @Author caiwen
 * @Date 2024 09 28 10 12
 **/
@Slf4j
public class StreamLinkRecorder extends Recorder {
    private String url;

    private StreamChannelTypeEnum channel;
    private boolean useProxy;

    public StreamLinkRecorder(Date regDate, String url) {
        this(regDate, url, false);
    }

    public StreamLinkRecorder(Date regDate, String url, boolean useProxy) {
        super(regDate);
        this.url = url;
        this.channel = StreamChannelTypeEnum.findChannelByUrl(url);
        this.useProxy = useProxy;
    }

    @Override
    public void doRecord(String savePath) {
        // 执行录制，长时间
        StreamerConfig streamerConfig = ConfigFetcher.getStreamerInfoByName(StreamerInfoHolder.getCurStreamerName());
        int totalCnt = RecordConstant.FFMPEG_RETRY_CNT;
        for (int i = 0; i < totalCnt; i++) {
            FfmpegRecordCmd rfCmd = new FfmpegRecordCmd(buildCmd(savePath));
            rfCmd.execute();

            if (!rfCmd.isExitNormal()) {
                log.error("download stream fail, savePath: {}", savePath);
                throw new StreamerRecordException(ErrorEnum.FFMPEG_EXECUTE_ERROR);
            }

            if (rfCmd.isEndNormal() || BooleanUtils.isNotTrue(streamerConfig.isRecordWhenOnline())) {
                log.info("download stream completed, savePath: {}", savePath);
                return;
            }

            // 录制直播，但是由于网络原因进行重试
            log.info("download stream timeout, will reconnect, savePath: {}, {}/{}", savePath, i + 1, totalCnt);
        }
    }

    private String buildCmd(String savePath) {
        List<String> extraArgs = Lists.newArrayList();

        // twitch额外参数
        if (channel == StreamChannelTypeEnum.TWITCH) {
            extraArgs.add("--twitch-disable-ads");
            String authorization = ConfigFetcher.getInitConfig().getTwitchAuthorization();
            if (StringUtils.isNotBlank(authorization)) {
                extraArgs.add(String.format("\"--twitch-api-header=Authorization=%s\"", authorization));
            }
        }

        // 录制代理
        String httpProxy = ConfigFetcher.getInitConfig().getHttpProxy();
        if (useProxy && StringUtils.isNotBlank(httpProxy)) {
            extraArgs.add(String.format("--http-proxy \"%s\"", httpProxy));
        }

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
//                "-rw_timeout 20000000",
                "-hide_banner",
                "-i -",
                "-bufsize 10000k",
                "-c:v copy -c:a copy -c:s mov_text",
                channel == StreamChannelTypeEnum.TWITCH ? "-map 0:v -map 0:a" : "-map 0",
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
