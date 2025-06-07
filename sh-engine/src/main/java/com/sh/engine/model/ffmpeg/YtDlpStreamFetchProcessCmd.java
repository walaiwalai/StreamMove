package com.sh.engine.model.ffmpeg;

import org.apache.commons.lang3.StringUtils;

/**
 * @Author caiwen
 * @Date 2025 06 07 17 12
 **/
public class YtDlpStreamFetchProcessCmd extends AbstractCmd {
    private final StringBuilder sb = new StringBuilder();
    private String videoM3u8Url;
    private String audioM3u8Url;

    public YtDlpStreamFetchProcessCmd(String vodUrl) {
        super("");
        this.command = "yt-dlp -g -f \"bestvideo+bestaudio\" " + vodUrl;
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
        videoM3u8Url = split[0];
        audioM3u8Url = split[1];
    }

    public String getVideoM3u8Url() {
        return videoM3u8Url;
    }

    public String getAudioM3u8Url() {
        return audioM3u8Url;
    }
}
