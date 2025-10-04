package com.sh.engine.model.ffmpeg;

import com.google.common.collect.Lists;
import com.sh.config.utils.EnvUtil;
import lombok.Builder;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;

import java.util.List;

/**
 * @Author caiwen
 * @Date 2025 02 09 17 52
 **/
public class YtDlpVideoMetaProcessCmd extends AbstractYtDlpCmd {
    private YtDlpVideoMeta videoMeta;

    public YtDlpVideoMetaProcessCmd(String videoUrl, Integer channelType) {
        super("");
        this.command = "yt-dlp" + buildChannelOption(channelType) + "--print \"%(webpage_url)s\t%(timestamp)s\t%(thumbnail)s\" " + videoUrl;
    }

    @Override
    protected void processOutputLine(String line) {
        System.out.println(line);
        String[] infos = StringUtils.split(line, "\t");
        videoMeta = YtDlpVideoMeta.builder()
                .videoUrl(infos[0])
                .uploadTimeStamp(Long.parseLong(infos[1]) * 1000L)
                .thumbnailUrl(infos[2])
                .build();
    }

    @Override
    protected void processErrorLine(String line) {

    }

    public YtDlpVideoMeta getVideoMetaMap() {
        return videoMeta;
    }

    @Data
    @Builder
    public static class YtDlpVideoMeta {
        private String videoUrl;
        private String thumbnailUrl;
        private Long uploadTimeStamp;
    }
}
