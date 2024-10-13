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
 * @Author caiwen
 * @Date 2024 09 28 10 12
 **/
@Slf4j
public class StreamLinkRecorder extends Recorder {
    private String url;

    private StreamChannelTypeEnum channel;

    public StreamLinkRecorder(String savePath, Date regDate, String url) {
        super(savePath, regDate);
        this.url = url;
        channel = StreamChannelTypeEnum.findChannelByUrl(url);
    }

    @Override
    public void doRecord() throws Exception {
        String extraArguments = "";
        if (channel == StreamChannelTypeEnum.TWITCH) {
            extraArguments += "--twitch-disable-ads \"--twitch-api-header=Authorization=" + ConfigFetcher.getInitConfig().getTwitchAuthorization() + "\"";
        }

        File segFile = new File(savePath, VideoFileUtil.SEG_FILE_NAME);
        List<String> commands = Lists.newArrayList(
                "streamlink", extraArguments,
                url, "best",
                "--stdout", "|",
                "ffmpeg",
                "-y",
                "-v verbose",
                "-loglevel error",
                "-hide_banner",
                "-i -",
                "-bufsize 5000k",
                "-c:v copy -c:a copy -c:s mov_text",
                channel == StreamChannelTypeEnum.TWITCH ? "-map 0:v -map 0:a" : "-map 0",
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
