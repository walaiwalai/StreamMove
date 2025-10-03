package com.sh.engine.model.ffmpeg;

import com.google.common.collect.Lists;
import com.sh.config.manager.ConfigFetcher;
import com.sh.config.utils.EnvUtil;
import com.sh.engine.constant.StreamChannelTypeEnum;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.Arrays;
import java.util.List;

/**
 * @Author caiwen
 * @Date 2025 06 07 17 12
 **/
public class YtDlpVAMerProcessCmd extends AbstractCmd {
    private static final String accountSavePath = EnvUtil.getEnvValue("sh.account-save.path");

    private final StringBuilder sb = new StringBuilder();
    private List<String> mergeUrls = Lists.newArrayList();

    public YtDlpVAMerProcessCmd(String vodUrl, Integer channelType) {
        super("");
        this.command = buildCmd(vodUrl, channelType);
    }

    private String buildCmd(String vodUrl, Integer channelType) {
        String res = "yt-dlp -g " +
                " -f \"bestvideo[ext=mp4][vcodec*=avc1]+bestaudio[acodec*=aac]/best\" " +
                " -S \"vcodec:avc1:av01:vp9,acodec:aac,res:desc,filesize:desc\" " +
                vodUrl;
        if (channelType == StreamChannelTypeEnum.AFREECA_TV.getType()) {
            String soopUserName = ConfigFetcher.getInitConfig().getSoopUserName();
            String soopPassword = ConfigFetcher.getInitConfig().getSoopPassword();
            res = "yt-dlp -g --username " + soopUserName + " --password " + soopPassword +
                    " -f \"bestvideo[ext=mp4][vcodec*=avc1]+bestaudio[acodec*=aac]/best\" " +
                    " -S \"vcodec:avc1:av01:vp9,acodec:aac,res:desc,filesize:desc\" " +
                    vodUrl;
        } else if (channelType == StreamChannelTypeEnum.TWITCH.getType()) {
            File cookiesFile = new File(accountSavePath, "twitch-cookies.txt");
            if (cookiesFile.exists()) {
                res = "yt-dlp -g " +
                        " -f \"bestvideo[ext=mp4][vcodec*=avc1]+bestaudio[acodec*=aac]/best\" " +
                        " -S \"vcodec:avc1:av01:vp9,acodec:aac,res:desc,filesize:desc\" " +
                        " --cookies " + cookiesFile.getAbsolutePath() + " " +
                        vodUrl;
            }
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
        mergeUrls.addAll(Arrays.asList(split));
    }

    public List<String> getMergeUrls() {
        return mergeUrls;
    }
}
