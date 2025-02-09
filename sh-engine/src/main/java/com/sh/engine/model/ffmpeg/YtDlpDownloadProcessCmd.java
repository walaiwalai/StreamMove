package com.sh.engine.model.ffmpeg;

import lombok.extern.slf4j.Slf4j;

/**
 * @Author caiwen
 * @Date 2025 02 09 16 27
 **/
@Slf4j
public class YtDlpDownloadProcessCmd extends AbstractCmd {
    public YtDlpDownloadProcessCmd(String command) {
        super(command);
    }

    @Override
    protected void processOutputLine(String line) {
    }

    @Override
    protected void processErrorLine(String line) {
        log.info("ER-STREAM>>>>" + line);
    }

    public void execute(long timeoutSeconds) {
        try {
            super.execute(timeoutSeconds);
        } catch (Exception ignored) {
        }
    }
}
