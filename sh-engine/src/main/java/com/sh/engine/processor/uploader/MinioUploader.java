package com.sh.engine.processor.uploader;

import com.sh.config.manager.MinioManager;
import com.sh.config.utils.ExecutorPoolUtil;
import com.sh.config.utils.FileStoreUtil;
import com.sh.config.utils.VideoFileUtil;
import com.sh.engine.base.StreamerInfoHolder;
import com.sh.engine.constant.UploadPlatformEnum;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.FileUtils;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.Collection;
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * minio上传
 *
 * @Author caiwen
 * @Date 2024 10 19 15 55
 **/
@Slf4j
@Component
public class MinioUploader extends Uploader {
    private static final int MAX_RETRY_CNT = 10;

    @Override
    public String getType() {
        return UploadPlatformEnum.MINIO.getType();
    }

    @Override
    public void setUp() {

    }

    @Override
    public boolean upload(String recordPath) throws Exception {
        // 1.上传所有recordPath下的所有文件
        Collection<File> videos = FileUtils.listFiles(new File(recordPath), new String[]{"ts"}, false)
                .stream()
                .sorted(Comparator.comparingInt(v -> VideoFileUtil.genIndex(v.getName())))
                .collect(Collectors.toList());
        if (CollectionUtils.isEmpty(videos)) {
            return true;
        }

        int totalCnt = videos.size();
        String timeV = new File(recordPath).getName();
        String streamerName = StreamerInfoHolder.getCurStreamerName();
        String targetMinioDir = streamerName + "/" + timeV;

        CountDownLatch countDownLatch = new CountDownLatch(totalCnt);
        AtomicInteger cnt = new AtomicInteger(0);
        for (File segVideo : videos) {
            CompletableFuture.supplyAsync(() -> {
                        return uploadWithRetry(segVideo, targetMinioDir);
                    }, ExecutorPoolUtil.getUploadPool())
                    .whenComplete((isSuccess, throwbale) -> {
                        if (isSuccess) {
                            log.info("finish uploading ts to minio, {}/{}", cnt.getAndIncrement(), totalCnt);
                        } else {
                            log.error("error uploading ts to minio, file: {}", segVideo.getAbsolutePath());
                        }
                        countDownLatch.countDown();
                    });
        }
        countDownLatch.await();

        // 2. 上传完成后，再上传一个完成上传的标志
        File finishFlag = new File(recordPath, "finish-flag.txt");
        FileStoreUtil.saveToFile(finishFlag, "done");
        return MinioManager.uploadFile(finishFlag, targetMinioDir);
    }

    private boolean uploadWithRetry(File segVideo, String targetMinioDir) {
        int reTryCnt = 0;
        for (int i = 0; i < MAX_RETRY_CNT; i++) {
            boolean isFinish = MinioManager.uploadFile(segVideo, targetMinioDir);
            if (isFinish) {
                return true;
            }

            reTryCnt++;
            log.info("error uploading ts to minio, file: {}, {}/{}", segVideo.getAbsolutePath(), reTryCnt, MAX_RETRY_CNT);
        }
        return false;
    }
}
