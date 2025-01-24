package com.sh.engine.model.ffmpeg;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * @Author caiwen
 * @Date 2024 10 25 22 57
 **/
@Slf4j
public class FfmpegRecordCmd extends CommonCmd {
    /**
     * 命令执行结果
     */
    private CompletableFuture<Void> future;


    public FfmpegRecordCmd(String command) {
        super(command, false, true);
    }

    @Override
    protected void doExecute(long timeout, TimeUnit unit) throws Exception {
        // 后台执行命令
        this.future = super.start(null, null);
        future.get(timeout, unit);
        super.waitExit();
    }

    public void executeAsync() {
        this.future = super.start(null, null);
    }

    public void waitTillEnd(long timeout, TimeUnit unit) {
        try {
            this.future.get(timeout, unit);
            super.waitExit();
        } catch (Exception e) {
            close();
        }
    }

    public void kill() {
        close();
    }

    public boolean isExitNormal() {
        return getExitCode() == 0;
    }
}
