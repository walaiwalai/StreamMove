package com.sh.engine.model.record;

import com.sh.config.utils.ExecutorPoolUtil;
import com.sh.config.utils.VideoFileUtils;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Date;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 视频切片
 * @Author caiwen
 * @Date 2024 09 28 10 18
 **/
@Slf4j
public class VideoSegRecorder extends Recorder {
    OkHttpClient CLIENT = new OkHttpClient();
    private static final int SEG_DOWNLOAD_RETRY = 5;

    private List<TsRecordInfo> tsViews;

    public VideoSegRecorder(String savePath, Date regDate, List<TsRecordInfo> tsViews) {
        super(savePath, regDate);
        this.tsViews = tsViews;
    }

    @Override
    public void doRecord() throws Exception{
        String dirName = savePath;
        int total = tsViews.stream().mapToInt(TsRecordInfo::getCount).sum();

        // 每个streamer单独起一个线程池进行下载
        ExecutorService downloadPool = ExecutorPoolUtil.getDownloadPool();

        CountDownLatch downloadLatch = new CountDownLatch(total);
        log.info("total ts count is: {}, begin download...", total);
        AtomicInteger index = new AtomicInteger(1);
        for (TsRecordInfo tsView : tsViews) {
            log.info("download ts record, url: {}, size: {}", tsView.getTsFormatUrl(), tsView.getCount());
            for (int i = 1; i < tsView.getCount() + 1; i++) {
                String segTsUrl = tsView.genTsUrl(i);
                CompletableFuture.supplyAsync(() -> {
                            File targetFile = new File(dirName, VideoFileUtils.genSegName(index.getAndIncrement()));
                            if (targetFile.exists()) {
                                log.info("ts file existed, path: {}", targetFile.getAbsolutePath());
                                return true;
                            }
                            return downloadTsSeg(segTsUrl, targetFile);
                        }, downloadPool)
                        .whenComplete((isSuccess, throwable) -> {
                            downloadLatch.countDown();
                        });
            }
        }

        // 等待视频下载完成
        downloadLatch.await();
    }

    private boolean downloadTsSeg(String targetUrl, File targetFile) {
        for (int i = 0; i < SEG_DOWNLOAD_RETRY; i++) {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
            }

            Request request = new Request.Builder().url(targetUrl).build();
            try (Response response = CLIENT.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    try (FileOutputStream fos = new FileOutputStream(targetFile)) {
                        fos.write(response.body().bytes());
                    }
                    log.info("Seg Video Download finish, filePath: {}", targetFile.getAbsolutePath());
                    return true;
                } else {
                    log.error("Seg Video download failed, url: {}, retry: {}/{}", targetUrl, i + 1, SEG_DOWNLOAD_RETRY);
                }
            } catch (IOException e) {
                log.error("Seg Video calling error, url: {}, retry: {}/{}", targetUrl, i + 1, SEG_DOWNLOAD_RETRY, e);
            }
        }
        return false;
    }

    /**
     * 视频切片
     *
     * @Author caiwen
     * @Date 2023 12 29 12 05
     **/
    @Builder
    @Data
    public static class TsRecordInfo {
        private Integer count;
        private String tsFormatUrl;

        public String genTsUrl(int tsNo) {
            return String.format(tsFormatUrl, tsNo);
        }
    }
}
