package com.sh.engine.model.ffmpeg;

import lombok.extern.slf4j.Slf4j;

/**
 * ffmpeg执行命令
 *
 * @Author caiwen
 * @Date 2024 10 26 10 37
 **/
@Slf4j
public class FFmpegProcessCmd extends AbstractCmd {
    private boolean openIs;
    private boolean openRs;

    public FFmpegProcessCmd(String command) {
        this(command, false, true);
    }

    public FFmpegProcessCmd(String command, boolean openIs, boolean openRs) {
        super(command);
        this.openIs = openIs;
        this.openRs = openRs;
    }


    @Override
    protected void processOutputLine(String line) {
        if (openIs) {
            log.info("OT-STREAM>>>>" + line);
        }
    }

    @Override
    protected void processErrorLine(String line) {
        if (openRs) {
            log.info("ER-STREAM>>>>" + line);
        }
    }

    public boolean isEndNormal() {
        return super.isNormalExit();
    }

    public static void main(String[] args) {
        String command = "ffmpeg -y -loglevel error -f concat -safe 0 -i G:\\stream_record\\download\\TheShy\\2024-10-15-22-00-00\\tmp\\tmp-2-merge.txt " +
                " -c:v copy -c:a copy G:\\stream_record\\test.ts";
        FFmpegProcessCmd processCmd = new FFmpegProcessCmd(command, true, true);
        processCmd.execute(10);
        System.out.println(processCmd.isEndNormal());
    }
}
