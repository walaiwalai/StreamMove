package com.sh.schedule.worker;

import com.sh.config.model.storage.FileStatusModel;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FileCleanWorkerTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void appliesFailedUploadRetentionThresholds() {
        long keepHighlight = TimeUnit.HOURS.toMillis(6);
        long deleteFailed = TimeUnit.HOURS.toMillis(24);

        assertEquals(FileCleanWorker.CleanupAction.DELETE_SUCCEEDED,
                FileCleanWorker.decideCleanupAction(true, 0, keepHighlight, deleteFailed));
        assertEquals(FileCleanWorker.CleanupAction.KEEP_ALL,
                FileCleanWorker.decideCleanupAction(false,
                        TimeUnit.HOURS.toMillis(5), keepHighlight, deleteFailed));
        assertEquals(FileCleanWorker.CleanupAction.KEEP_HIGHLIGHT_ONLY,
                FileCleanWorker.decideCleanupAction(false,
                        TimeUnit.HOURS.toMillis(6), keepHighlight, deleteFailed));
        assertEquals(FileCleanWorker.CleanupAction.DELETE_FAILED,
                FileCleanWorker.decideCleanupAction(false,
                        TimeUnit.HOURS.toMillis(24), keepHighlight, deleteFailed));
    }

    @Test
    public void keepsOnlyHighlightAndInternalStatusFile() throws Exception {
        File recordDir = temporaryFolder.newFolder("2026-08-02-11-02-00");
        File highlight = new File(recordDir, "highlight.mp4");
        File status = new File(recordDir, "fileStatus.json");
        File rawVideo = new File(recordDir, "P01.mp4");
        File tempFile = new File(recordDir, "temp.txt");
        File coverDir = new File(recordDir, "DOU_YIN_WEB-cover");
        assertTrue(coverDir.mkdir());
        Files.write(highlight.toPath(), new byte[]{1});
        Files.write(status.toPath(), new byte[]{2});
        Files.write(rawVideo.toPath(), new byte[]{3});
        Files.write(tempFile.toPath(), new byte[]{4});
        Files.write(new File(coverDir, "highlight#1.jpg").toPath(), new byte[]{5});

        FileCleanWorker.keepHighlightAndStatusOnly(recordDir);

        assertTrue(highlight.isFile());
        assertTrue(status.isFile());
        assertFalse(rawVideo.exists());
        assertFalse(tempFile.exists());
        assertFalse(coverDir.exists());
        assertEquals(2, recordDir.listFiles().length);
    }

    @Test
    public void preservesFirstFailureTimeUntilPlatformSucceeds() {
        FileStatusModel status = new FileStatusModel();
        status.failPost("DOU_YIN_WEB", 100L);
        status.failPost("DOU_YIN_WEB", 200L);

        assertEquals(100L,
                status.getEarliestPostFailureTime(Arrays.asList("DOU_YIN_WEB")));

        status.finishPost("DOU_YIN_WEB");
        assertEquals(0L,
                status.getEarliestPostFailureTime(Arrays.asList("DOU_YIN_WEB")));
    }

    @Test
    public void persistsFirstFailureTimeInFileStatus() throws Exception {
        File recordDir = temporaryFolder.newFolder("2026-08-03-10-00-00");
        FileStatusModel status = new FileStatusModel();
        status.failPost("WECHAT_VIDEO_WEB", 123456L);
        status.writeSelfToFile(recordDir.getAbsolutePath());

        FileStatusModel restored = FileStatusModel.loadFromFile(recordDir.getAbsolutePath());

        assertEquals(123456L,
                restored.getEarliestPostFailureTime(Arrays.asList("WECHAT_VIDEO_WEB")));
    }
}
