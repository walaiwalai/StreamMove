package com.sh.engine.model.ffmpeg;

import com.google.common.collect.Lists;
import org.apache.commons.lang3.StringUtils;

import java.util.List;

/**
 * @Author caiwen
 * @Date 2025 02 09 17 11
 **/
public class YtDlpPlaylistProcessCmd extends AbstractCmd {
    private List<String> videoUrls = Lists.newArrayList();

    public YtDlpPlaylistProcessCmd(String channelUrl, int latestN) {
        super("");
        this.command = "yt-dlp --flat-playlist --get-url --playlist-end " + latestN + " " + channelUrl;
    }

    @Override
    protected void processOutputLine(String line) {
        videoUrls.add(StringUtils.trim(line));
    }

    @Override
    protected void processErrorLine(String line) {

    }

    public List<String> getVideoUrls() {
        return videoUrls;
    }

    public void execute(long timeoutSeconds) {
        super.execute(timeoutSeconds);
    }


    public static void main(String[] args) {
        YtDlpPlaylistProcessCmd ytDlpPlaylistProcessCmd = new YtDlpPlaylistProcessCmd(" https://www.youtube.com/@LOLREC/videos", 5);
        ytDlpPlaylistProcessCmd.execute(10);
        System.out.println(ytDlpPlaylistProcessCmd.getVideoUrls());
    }

}
