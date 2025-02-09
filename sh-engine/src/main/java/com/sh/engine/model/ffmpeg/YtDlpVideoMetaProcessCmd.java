package com.sh.engine.model.ffmpeg;

import com.google.common.collect.Lists;
import lombok.Builder;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;

import java.util.List;

/**
 * @Author caiwen
 * @Date 2025 02 09 17 52
 **/
public class YtDlpVideoMetaProcessCmd extends AbstractCmd {
    private List<YtDlpVideoMeta> videoMetas = Lists.newArrayList();

    public YtDlpVideoMetaProcessCmd(List<String> videoUrls) {
        super("");
        this.command = "yt-dlp --print \"%(webpage_url)s\t%(timestamp)s\t%(thumbnail)s\" " + StringUtils.join(videoUrls, " ");
    }

    @Override
    protected void processOutputLine(String line) {
        System.out.println(line);
        String[] infos = StringUtils.split(line, "\t");
        YtDlpVideoMeta videoMeta = YtDlpVideoMeta.builder()
                .videoUrl(infos[0])
                .uploadTimeStamp(Long.parseLong(infos[1]) * 1000L)
                .thumbnailUrl(infos[2])
                .build();

        videoMetas.add(videoMeta);
    }

    @Override
    protected void processErrorLine(String line) {

    }

    public List<YtDlpVideoMeta> getVideoMetaMap() {
        return videoMetas;
    }

    public void execute(long timeoutSeconds) {
        super.execute(timeoutSeconds);
    }

    @Data
    @Builder
    public static class YtDlpVideoMeta {
        private String videoUrl;
        private String thumbnailUrl;
        private Long uploadTimeStamp;
    }
}
