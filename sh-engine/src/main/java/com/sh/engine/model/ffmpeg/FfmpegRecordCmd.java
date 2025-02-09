package com.sh.engine.model.ffmpeg;

import lombok.extern.slf4j.Slf4j;

/**
 * @Author caiwen
 * @Date 2024 10 25 22 57
 **/
@Slf4j
public class FfmpegRecordCmd extends AbstractCmd {
    public FfmpegRecordCmd(String command) {
        super(command);
    }

    @Override
    protected void processOutputLine(String line) {
    }

    @Override
    protected void processErrorLine(String line) {
    }

    public void execute(long timeoutSeconds) {
        super.execute(timeoutSeconds);
    }

    public boolean isExitNormal() {
        return super.isNormalExit();
    }
}
