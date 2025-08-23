package com.sh.engine.command;

import com.sh.config.manager.ConfigFetcher;
import com.sh.engine.constant.StreamChannelTypeEnum;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

/**
 * @Author caiwen
 * @Date 2025 08 17 10 17
 **/
@Slf4j
public class StreamLinkUrlFetchCmd extends AbstractCmd {
    private final StringBuilder infoOutSb = new StringBuilder();
    private String streamUrl;

    public StreamLinkUrlFetchCmd(String url, String qualityParam) {
        super("");
        StreamChannelTypeEnum channelEnum = StreamChannelTypeEnum.findChannelByUrl(url);
        if (channelEnum == StreamChannelTypeEnum.AFREECA_TV) {
            this.command = buildSoopCommand(url, qualityParam);
        } else {
            this.command = "streamlink --stream-url " + url + " " + qualityParam;
        }
    }

    @Override
    protected void processOutputLine(String line) {
        infoOutSb.append(line);
    }

    @Override
    protected void processErrorLine(String line) {

    }

    public void execute(long timeoutSeconds) {
        super.execute(timeoutSeconds);
        this.streamUrl = infoOutSb.toString().trim();
    }

    private String buildSoopCommand(String url, String qualityParam) {
        String soopUserName = ConfigFetcher.getInitConfig().getSoopUserName();
        String soopPassword = ConfigFetcher.getInitConfig().getSoopPassword();
        if (StringUtils.isNotBlank(soopUserName) && StringUtils.isNotBlank(soopPassword)) {
            return "streamlink --soop-username " + soopUserName + " --soop-password " + soopPassword + " --stream-url " + url + " " + qualityParam;
        } else {
            return "streamlink --stream-url " + url + " " + qualityParam;
        }
    }

    public String getStreamUrl() {
        return streamUrl;
    }

    public static void main(String[] args) {
        StreamLinkUrlFetchCmd streamLinkUrlFetchCmd = new StreamLinkUrlFetchCmd("https://www.huya.com/8952311111", "best");
        streamLinkUrlFetchCmd.execute(10);
        System.out.println(streamLinkUrlFetchCmd.getStreamUrl());
    }
}
