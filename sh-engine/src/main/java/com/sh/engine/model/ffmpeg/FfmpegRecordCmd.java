package com.sh.engine.model.ffmpeg;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.CompletableFuture;

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

    /**
     * 是否推出正常
     */
    private boolean exitNormal = false;


    public FfmpegRecordCmd(String command) {
        super(command, false, true);
    }

    @Override
    protected void doExecute() {
        // 后台执行命令
        this.future = super.start(null, null);

        // 同步等待完成
        this.future.join();

        // 同步等待
        this.exitNormal = super.getPrExitCode() == 0;
    }

    public CompletableFuture<Void> executeAsync() {
        this.future = super.start(null, null);
        return this.future;
    }

    public void waitForEnd() {
        try {
            this.future.join();

            // 同步等待
            this.exitNormal = super.getPrExitCode() == 0;
        } finally {
            close();
        }
    }

    public boolean isExitNormal() {
        return exitNormal;
    }
}
