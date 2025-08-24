package com.sh.engine.model.ffmpeg;


import java.io.File;

/**
 * @Author caiwen
 * @Date 2025 08 09 00 39
 **/
public class Ts2Mp4ProcessCmd extends AbstractCmd {
    public Ts2Mp4ProcessCmd(File tsFile, File mp4File) {
        super("");
        this.command = "ffmpeg -i " + tsFile.getAbsolutePath() + " -c copy \"" + mp4File.getAbsolutePath() + "\"";
    }

    @Override
    protected void processOutputLine(String line) {

    }

    @Override
    protected void processErrorLine(String line) {

    }
}
