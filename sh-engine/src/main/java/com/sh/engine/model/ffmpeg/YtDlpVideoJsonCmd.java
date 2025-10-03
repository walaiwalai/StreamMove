package com.sh.engine.model.ffmpeg;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.sh.engine.constant.StreamChannelTypeEnum;
import org.springframework.beans.factory.annotation.Value;

import java.io.File;

public class YtDlpVideoJsonCmd extends AbstractCmd {
    @Value("${sh.account-save.path}")
    private String accountSavePath;

    private final StringBuilder sb = new StringBuilder();

    public YtDlpVideoJsonCmd(String vodUrl, Integer channelType) {
        super("");
        this.command = buildCmd(vodUrl, channelType);
    }

    private String buildCmd(String vodUrl, Integer channelType) {
        String res = "yt-dlp -j " + vodUrl;
        if (channelType == StreamChannelTypeEnum.TWITCH.getType()) {
            File cookiesFile = new File(accountSavePath, "twitch-cookies.txt");
            if (cookiesFile.exists()) {
                res = "yt-dlp -j --cookies " + cookiesFile.getAbsolutePath() + " " + vodUrl;
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

    public JSONObject getMeta() {
        return JSON.parseObject(sb.toString());
    }
}
