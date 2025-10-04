package com.sh.engine.model.ffmpeg;

import lombok.extern.slf4j.Slf4j;

/**
 * @Author caiwen
 * @Date 2025 08 17 09 49
 **/
@Slf4j
public class StreamBitrateCmd extends AbstractCmd {
    private final StringBuilder infoOutSb = new StringBuilder();

    /**
     * 码率 kb/s
     */
    private int kbitrate;

    public StreamBitrateCmd(String streamUrl) {
        super("ffprobe -v error -select_streams v:0 -show_entries stream=bit_rate -of default=noprint_wrappers=1:nokey=1 -i \"" + streamUrl + "\"");
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

        String bitrateStr = infoOutSb.toString().trim();
        this.kbitrate = (int) Long.parseLong(bitrateStr) / 1000;
    }

    public int getKbitrate() {
        return kbitrate;
    }
}
