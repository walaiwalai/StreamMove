package com.sh.engine.model.ffmpeg;

import org.junit.Assert;
import org.junit.Test;

public class VideoSizeDetectCmdTest {

    @Test
    public void shouldQuoteVideoPathContainingSpaces() {
        VideoSizeDetectCmd command = new VideoSizeDetectCmd(
                "G:\\stream\\record with spaces\\P01.mp4");

        Assert.assertTrue(command.command.endsWith(
                "\"G:\\stream\\record with spaces\\P01.mp4\""));
    }
}
