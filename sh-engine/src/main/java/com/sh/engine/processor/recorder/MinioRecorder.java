package com.sh.engine.processor.recorder;

import com.sh.config.manager.MinioManager;
import com.sh.config.utils.ExecutorPoolUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;

import java.util.Date;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 特殊：从minio上下载文件
 *
 * @Author caiwen
 * @Date 2024 10 19 16 36
 **/
@Slf4j
public class MinioRecorder extends Recorder {
    private String objDir;


    public MinioRecorder(String savePath, Date regDate, String objDir) {
        super(savePath, regDate);
        this.objDir = objDir;
    }

    @Override
    public void doRecord() throws Exception {
        List<String> objNames = MinioManager.listObjectNames(objDir);
        if (CollectionUtils.isEmpty(objNames)) {
            return;
        }

        int totalCnt = objNames.size();
        CountDownLatch countDownLatch = new CountDownLatch(totalCnt);
        AtomicInteger cnt = new AtomicInteger(0);
        for (String objName : objNames) {
            CompletableFuture.supplyAsync(() -> {
                        return MinioManager.downloadFile(objName, savePath);
                    }, ExecutorPoolUtil.getDownloadPool())
                    .whenComplete((isSuccess, throwbale) -> {
                        if (isSuccess) {
                            log.info("finish downloading ts from minio, {}/{}", cnt.incrementAndGet(), totalCnt);
                        } else {
                            log.error("error downloading ts from minio, file: {}", objName);
                        }
                        countDownLatch.countDown();
                    });
        }
        countDownLatch.await();
    }
}
