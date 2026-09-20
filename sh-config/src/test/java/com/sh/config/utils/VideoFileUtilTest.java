package com.sh.config.utils;

import org.apache.commons.io.FileUtils;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class VideoFileUtilTest {

    @Test
    public void shouldListSegmentMarkerInsideMp4FileName() throws Exception {
        Path directory = Files.createTempDirectory("indexed-mp4-files-");
        try {
            Files.createFile(directory.resolve("2-P02-1080P.mp4"));
            Files.createFile(directory.resolve("1-P01-1080P.mp4"));
            Files.createFile(directory.resolve("highlight.mp4"));

            List<File> files = VideoFileUtil.listIndexedMp4Files(directory.toString());

            Assert.assertEquals(2, files.size());
            Assert.assertEquals("1-P01-1080P.mp4", files.get(0).getName());
            Assert.assertEquals("2-P02-1080P.mp4", files.get(1).getName());
        } finally {
            FileUtils.deleteDirectory(directory.toFile());
        }
    }
}
