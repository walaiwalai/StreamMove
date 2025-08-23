package com.sh.engine.command;

import com.google.common.collect.Lists;
import com.sh.config.manager.ConfigFetcher;
import com.sh.engine.constant.StreamChannelTypeEnum;
import org.apache.commons.lang3.StringUtils;

import java.util.List;

/**
 * @Author caiwen
 * @Date 2025 06 07 17 12
 **/
public class YtDlpVASepProcessCmd extends AbstractCmd {
    private final StringBuilder sb = new StringBuilder();
    private List<String> videoM3u8Urls = Lists.newArrayList();
    private List<String> audioM3u8Urls = Lists.newArrayList();

    public YtDlpVASepProcessCmd(String vodUrl, Integer channelType) {
        super("");
        this.command = buildCmd(vodUrl, channelType);
    }

    private String buildCmd(String vodUrl, Integer channelType) {
        String res;
        if (channelType == StreamChannelTypeEnum.AFREECA_TV.getType()) {
            String soopUserName = ConfigFetcher.getInitConfig().getSoopUserName();
            String soopPassword = ConfigFetcher.getInitConfig().getSoopPassword();
            res = "yt-dlp -g --username " + soopUserName + " --password " + soopPassword + " -f \"bestvideo+bestaudio\" " + vodUrl;
        } else {
            res = "yt-dlp -g -f \"bestvideo+bestaudio\" " + vodUrl;
        }
        return res;
    }

    @Override
    protected void processOutputLine(String line) {
        sb.append(line).append("\n");
    }

    @Override
    protected void processErrorLine(String line) {

    }


    public void execute(long timeoutSeconds) {
        super.execute(timeoutSeconds);

        // 执行完成后
        String[] split = StringUtils.trim(sb.toString()).split("\n");
        for (int i = 0; i < split.length / 2; i++) {
            videoM3u8Urls.add(split[i * 2]);
            audioM3u8Urls.add(split[i * 2 + 1]);
        }
    }

    public List<String> getVideoM3u8Urls() {
        return videoM3u8Urls;
    }

    public List<String> getAudioM3u8Urls() {
        return audioM3u8Urls;
    }
}
