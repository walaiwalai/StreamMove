package com.sh.engine.processor.plugin.lol;

import com.sh.engine.model.ffmpeg.TimestampScreenshotCmd;
import org.apache.commons.io.FileUtils;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.Semaphore;

import static com.sh.engine.processor.plugin.lol.LolHighlightConstants.SEEK_PREROLL_SECONDS;
import static com.sh.engine.processor.plugin.lol.LolHighlightConstants.SNAPSHOT_PARALLELISM;

/**
 * 按绝对时间抽取图片，并在快速定位失败时用少量预滚解码重试。
 */
@Component
public class LolTimestampFrameExtractor {
    private static final Semaphore FFMPEG_PERMITS = new Semaphore(SNAPSHOT_PARALLELISM);

    public File extract(File sourceVideo,
                        int timestampSeconds,
                        String cropExpression,
                        File targetDirectory) {
        targetDirectory.mkdirs();
        File targetImage = new File(
                targetDirectory,
                String.format(Locale.ROOT, "%s@%06d.jpg", prefix(sourceVideo), timestampSeconds));

        acquirePermit();
        try {
            FileUtils.deleteQuietly(targetImage);
            if (tryExecute(sourceVideo, targetImage, timestampSeconds, cropExpression, 0)) {
                return targetImage;
            }

            FileUtils.deleteQuietly(targetImage);
            if (tryExecute(
                    sourceVideo, targetImage, timestampSeconds, cropExpression, SEEK_PREROLL_SECONDS)) {
                return targetImage;
            }
            throw new LolAdaptiveScanException(
                    "cannot extract frame at " + timestampSeconds + "s from " + sourceVideo.getAbsolutePath());
        } catch (LolAdaptiveScanException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new LolAdaptiveScanException(
                    "error extracting frame at " + timestampSeconds + "s from " + sourceVideo.getAbsolutePath(), e);
        } finally {
            FFMPEG_PERMITS.release();
        }
    }

    private boolean tryExecute(File sourceVideo,
                               File targetImage,
                               int timestampSeconds,
                               String cropExpression,
                               int preRollSeconds) {
        try {
            return execute(
                    sourceVideo, targetImage, timestampSeconds, cropExpression, preRollSeconds);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private boolean execute(File sourceVideo,
                            File targetImage,
                            int timestampSeconds,
                            String cropExpression,
                            int preRollSeconds) {
        TimestampScreenshotCmd command = new TimestampScreenshotCmd(
                sourceVideo, targetImage, timestampSeconds, cropExpression, preRollSeconds);
        command.execute(60);
        return command.isEndNormal() && targetImage.isFile() && targetImage.length() > 0;
    }

    private void acquirePermit() {
        try {
            FFMPEG_PERMITS.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LolAdaptiveScanException("interrupted while waiting to extract frame", e);
        }
    }

    private String prefix(File sourceVideo) {
        String name = sourceVideo.getName();
        int suffixStart = name.lastIndexOf('.');
        return suffixStart > 0 ? name.substring(0, suffixStart) : name;
    }
}
