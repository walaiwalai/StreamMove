package com.sh.engine.processor.recorder;

import com.google.common.collect.Lists;
import com.sh.config.manager.ConfigFetcher;
import com.sh.config.utils.VideoFileUtil;
import com.sh.engine.constant.StreamChannelTypeEnum;
import com.sh.engine.model.ffmpeg.FfmpegCmd;
import com.sh.engine.util.CommandUtil;
import lombok.extern.slf4j.Slf4j;
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

    public StreamLinkRecorder(String savePath, Date regDate, String url) {
        this(savePath, regDate, url, false);
    }

    public StreamLinkRecorder(String savePath, Date regDate, String url, boolean useProxy) {
        super(savePath, regDate);
        this.url = url;
        this.channel = StreamChannelTypeEnum.findChannelByUrl(url);
        this.useProxy = useProxy;
    }

    @Override
    public void doRecord() throws Exception {
        List<String> extraArgs = Lists.newArrayList();
        if (channel == StreamChannelTypeEnum.TWITCH) {
            extraArgs.add("--twitch-disable-ads");
            String authorization = ConfigFetcher.getInitConfig().getTwitchAuthorization();
            if (StringUtils.isNotBlank(authorization)) {
                extraArgs.add(String.format("\"--twitch-api-header=Authorization=%s\"", authorization));
            }
        }

        String httpProxy = ConfigFetcher.getInitConfig().getHttpProxy();
        if (useProxy && StringUtils.isNotBlank(httpProxy)) {
            extraArgs.add(String.format("--http-proxy \"%s\"", httpProxy));
        }

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
                "-rw_timeout 30000000",
                "-hide_banner",
                "-i -",
                "-bufsize 10000k",
                "-c:v copy -c:a copy -c:s mov_text",
                channel == StreamChannelTypeEnum.TWITCH ? "-map 0:v -map 0:a" : "-map" +
                        " 0",
                "-f segment",
                "-segment_time 4",
                "-segment_start_number 1",
                "-segment_format mp4",
                "-movflags +faststart",
                "-reset_timestamps 1",
                "\"" + segFile.getAbsolutePath() + "\""
        );
        String cmd = StringUtils.join(commands, " ");

        // 执行录制，长时间
        Integer resCode = CommandUtil.cmdExec(new FfmpegCmd(cmd));
        if (resCode == 0) {
            log.info("download stream completed, savePath: {}, code: {}", savePath, resCode);
        } else {
            log.error("download stream fail, savePath: {}, code: {}", savePath, resCode);
        }
    }

    private void doSaveMetaData() {
        File metadataFile = new File(savePath, "metadata.json");
        List<String> commands = Lists.newArrayList(
                "streamlink", "--json",
                url,
                "|", "jq .metadata >", metadataFile.getAbsolutePath()
        );
        String cmd = StringUtils.join(commands, " ");

        Integer resCode = CommandUtil.cmdExec(new FfmpegCmd(cmd));
        if (resCode == 0) {
            log.info("load metadata success, savePath: {}, code: {}", savePath, resCode);
        } else {
            log.error("load metadata fail, savePath: {}, code: {}", savePath, resCode);
        }
    }
}
