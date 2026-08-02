package com.sh.engine.model.ffmpeg;

import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertTrue;

public class ScreenshotCmdTest {

    @Test
    public void quotesFilterExpressionForShellExecution() {
        ScreenshotCmd command = new ScreenshotCmd(
                new File("/home/admin/stream/download/highlight.mp4"),
                new File("/home/admin/stream/download/DOU_YIN_WEB-cover"),
                0, 1, "scale=trunc(iw/2)*2:trunc(ih/2)*2", 1, 1, true);

        assertTrue(command.command.contains(
                "-vf \"scale=trunc(iw/2)*2:trunc(ih/2)*2,fps=1/1,format=yuv420p\""));
    }

    @Test
    public void quotesFastScreenshotFilterExpressionToo() {
        ScreenshotCmd command = new ScreenshotCmd(
                new File("/home/admin/stream/download/P01.mp4"),
                new File("/home/admin/stream/download/snapshots"),
                0, 10, "crop=270:290:in_w*86/100:in_h*3/16", 4, 1, false);

        assertTrue(command.command.contains(
                "-vf \"crop=270:290:in_w*86/100:in_h*3/16,fps=1/4\""));
    }
}
