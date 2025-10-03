package com.sh.engine.model.ffmpeg;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;

public class YtDlpVideoJsonCmd extends AbstractCmd{
    private final StringBuilder sb = new StringBuilder();

    public YtDlpVideoJsonCmd(String vodUrl) {
        super("yt-dlp -j " + vodUrl);
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
