package com.sh.engine.processor.recorder.danmu;

import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.sh.engine.constant.RecordConstant.DAMAKU_TXT_FILE_FORMAT;

/**
 * 弹幕文件切换策略
 * 负责根据TS文件的变化来切换弹幕文件
 */
@Slf4j
public class DanmakuSwitchStrategy {
    /**
     * 弹幕文件索引计数器
     */
    private AtomicInteger danmuFileIndex = new AtomicInteger(1);
    
    /**
     * 定时任务调度器（用于周期性生成文件）
     */
    private ScheduledExecutorService scheduler;
    
    /**
     * 保存路径
     */
    private String savePath;
    
    /**
     * 关联的弹幕录制器
     */
    private DanmakuRecorder danmakuRecorder;
    
    public DanmakuSwitchStrategy(DanmakuRecorder danmakuRecorder) {
        if (danmakuRecorder == null) {
            throw new IllegalArgumentException("danmakuRecorder cannot be null");
        }
        this.danmakuRecorder = danmakuRecorder;
    }
    
    /**
     * 初始化弹幕录制器
     */
    public void init(String savePath) {
        this.savePath = savePath;

        File txtFile = new File(savePath, String.format(DAMAKU_TXT_FILE_FORMAT, danmuFileIndex.getAndIncrement()));
        danmakuRecorder.init(txtFile);
    }
    
    /**
     * 启动弹幕录制器和文件切换任务
     */
    public void start() {
        danmakuRecorder.start();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "damaku-scheduler-switch");
            t.setDaemon(true);
            return t;
        });

        // 每3秒检查一次TS文件变化
        this.scheduler.scheduleAtFixedRate(
                this::checkTsFilesAndCreateTxt,
                0,
                3,
                TimeUnit.SECONDS
        );

        // 打印一下录制细节
        this.scheduler.scheduleAtFixedRate(
                danmakuRecorder::showRecordDetail,
                30,
                30,
                TimeUnit.SECONDS
        );
    }
    
    /**
     * 停止弹幕录制器和文件切换任务
     */
    public void stop() {
        danmakuRecorder.close();
        if (scheduler != null) {
            scheduler.shutdownNow();
            try {
                // 等待任务终止（最多等1秒，避免阻塞）
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    log.error("close danmu scheduler failed");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            scheduler = null;
            log.info("close danmu scheduler success");
        }
    }
    
    /**
     * 检查TS文件变化，当有新的TS文件产生时通知弹幕录制器切换文件
     */
    private void checkTsFilesAndCreateTxt() {
        if (Thread.currentThread().isInterrupted()) {
            return;
        }
        File[] tsFiles = new File(savePath).listFiles((d, name) -> name.endsWith(".ts"));
        if (tsFiles == null) {
            return;
        }

        int createdTxtCount = danmuFileIndex.get() - 1;

        // 如果TS文件数量大于已创建的txt文件数量，说明有新的TS文件
        if (tsFiles.length > createdTxtCount) {
            for (int i = createdTxtCount; i < tsFiles.length; i++) {
                File txtFile = new File(savePath, String.format(DAMAKU_TXT_FILE_FORMAT, danmuFileIndex.getAndIncrement()));
                danmakuRecorder.refresh(txtFile);
            }
        }
    }
}