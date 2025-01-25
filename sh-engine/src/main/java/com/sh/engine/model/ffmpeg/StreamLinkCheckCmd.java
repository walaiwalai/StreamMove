package com.sh.engine.model.ffmpeg;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 直播是否在线命令
 *
 * @Author caiwen
 * @Date 2024 10 26 10 45
 **/
@Slf4j
public class StreamLinkCheckCmd extends CommonCmd {
    /**
     * 流是否在线
     */
    private boolean streamOnline = false;

    public StreamLinkCheckCmd(String command) {
        super(command, true, false);
    }

    @Override
    protected void doExecute(long timeout, TimeUnit unit) throws Exception {
        StringBuilder output = new StringBuilder();
        CompletableFuture<Void> future = super.start((line) -> {
            output.append(line).append("\n");
        }, null);
        future.get(timeout, unit);
        super.waitExit();
        streamOnline = output.toString().contains("Available");
    }

    public boolean isStreamOnline() {
        return streamOnline;
    }

    public static void main(String[] args) {
        StreamLinkCheckCmd checkCmd = new StreamLinkCheckCmd("streamlink https://www.huya.com/chuhe");
        checkCmd.execute(10, TimeUnit.SECONDS);

        System.out.println(checkCmd.isStreamOnline());
    }
}
