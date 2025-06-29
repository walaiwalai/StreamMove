package com.sh.engine.model.ffmpeg;

import com.sh.config.exception.StreamerRecordException;
import com.sh.config.manager.ConfigFetcher;
import com.sh.engine.constant.StreamChannelTypeEnum;
import com.sh.engine.util.RegexUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.List;

/**
 * 直播是否在线命令
 *
 * @Author caiwen
 * @Date 2024 10 26 10 45
 **/
@Slf4j
public class StreamLinkCheckCmd extends AbstractCmd {
    private static final String regex = "(\\w+)\\s*\\((?:worst|best)\\)";
    private final StringBuilder infoOutSb = new StringBuilder();

    /**
     * 流是否在线
     */
    private boolean streamOnline = false;
    private String worstResolution;
    private String bestResolution;


    public StreamLinkCheckCmd(String url) {
        super("");
        StreamChannelTypeEnum channelEnum = StreamChannelTypeEnum.findChannelByUrl(url);
        if (channelEnum == StreamChannelTypeEnum.AFREECA_TV) {
            this.command = buildSoopCommand(url);
        } else {
            this.command = "streamlink " + url;
        }
    }

    public void execute(long timeoutSeconds) {
        try {
            super.execute(timeoutSeconds);
        } catch (StreamerRecordException recordException) {
            if (getExitCode() == 1) {
                // 直播流没有开播，正常退出
                return;
            }
            throw recordException;
        }

        // 执行完成后
        if (streamOnline) {
            try {
                List<String> matchList = RegexUtil.getMatchList(infoOutSb.toString(), regex, false);
                worstResolution = matchList.get(0);
                bestResolution = matchList.get(1);
            } catch (Exception ignored) {
                log.error("parse stream quality error, output: {}", infoOutSb);
            }
        }
    }

    public boolean isStreamOnline() {
        return streamOnline;
    }

    public String getWorstResolution() {
        return worstResolution;
    }

    public String getBestResolution() {
        return bestResolution;
    }

    private String buildSoopCommand(String url) {
        String soopUserName = ConfigFetcher.getInitConfig().getSoopUserName();
        String soopPassword = ConfigFetcher.getInitConfig().getSoopPassword();
        if (StringUtils.isNotBlank(soopUserName) && StringUtils.isNotBlank(soopPassword)) {
            return "streamlink --soop-username " + soopUserName + " --soop-password " + soopPassword + " " + url;
        } else {
            return "streamlink " + url;
        }
    }

    @Override
    protected void processOutputLine(String line) {
        streamOnline = line.contains("Available");
        infoOutSb.append(line);
    }

    @Override
    protected void processErrorLine(String line) {
        log.info("ER-STREAM>>>>" + line);
    }
}
