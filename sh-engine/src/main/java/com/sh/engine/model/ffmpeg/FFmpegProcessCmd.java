package com.sh.engine.model.ffmpeg;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * ffmpeg执行命令
 *
 * @Author caiwen
 * @Date 2024 10 26 10 37
 **/
@Slf4j
public class FFmpegProcessCmd extends CommonCmd {
    public FFmpegProcessCmd(String command) {
        super(command, true, true);
    }

    public FFmpegProcessCmd(String command, boolean openIs, boolean openRs) {
        super(command, openIs, openRs);
    }

    @Override
    protected void doExecute(long timeout, TimeUnit unit) throws Exception {
        CompletableFuture<Void> future = super.start(null, null);
        future.get(timeout, unit);

        super.waitExit();
    }

    public boolean isEndNormal() {
        return getExitCode() == 0;
    }

    public static void main(String[] args) {
        String command = "ffmpeg -y -loglevel error -f concat -safe 0 -i G:\\stream_record\\download\\TheShy\\2024-10-15-22-00-00\\tmp\\tmp-2-merge.txt " +
                " -c:v copy -c:a copy G:\\stream_record\\test.ts";
        FFmpegProcessCmd processCmd = new FFmpegProcessCmd(command);
        processCmd.execute(10, TimeUnit.SECONDS);
        System.out.println(processCmd.isEndNormal());
    }
}
