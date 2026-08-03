package com.sh.schedule.worker;

import cn.hutool.extra.spring.SpringUtil;
import com.google.common.collect.Lists;
import com.sh.config.manager.ConfigFetcher;
import com.sh.config.manager.StatusManager;
import com.sh.config.model.config.InitConfig;
import com.sh.config.model.config.StreamerConfig;
import com.sh.config.model.storage.FileStatusModel;
import com.sh.engine.constant.RecordConstant;
import com.sh.message.service.MsgSendService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.DirectoryFileFilter;
import org.apache.commons.io.filefilter.NameFileFilter;
import org.quartz.JobExecutionContext;
import org.springframework.core.env.Environment;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;


/**
 * @author caiWen
 * @date 2023/2/1 23:14
 */
@Slf4j
public class FileCleanWorker extends ProcessWorker {
    private static final String STATUS_FILE_NAME = "fileStatus.json";
    private static final int DEFAULT_KEEP_HIGHLIGHT_HOURS = 6;
    private static final int DEFAULT_DELETE_FAILED_HOURS = 24;

    @Override
    protected void executeJob(JobExecutionContext jobExecutionContext) {
        clear();
    }

    private void clear() {
        StatusManager statusManager = SpringUtil.getBean(StatusManager.class);
        MsgSendService msgSendService = SpringUtil.getBean(MsgSendService.class);
        CleanupThreshold threshold = resolveThreshold(ConfigFetcher.getInitConfig());
        long now = System.currentTimeMillis();

        List<File> streamerFiles = listRecordDir();
        for (File streamerFile : streamerFiles) {
            Collection<File> statusFiles = FileUtils.listFiles(streamerFile, new NameFileFilter(STATUS_FILE_NAME),
                    DirectoryFileFilter.INSTANCE);
            for (File statusFile : statusFiles) {
                File recordDir = statusFile.getParentFile();
                String curRecordPath = recordDir.getAbsolutePath();
                String streamerName = statusFile.getParentFile().getParentFile().getName();
                try {
                    if (statusManager.isPathOccupied(curRecordPath, streamerName)) {
                        log.info("Record path is occupied, skip cleaning: {}", curRecordPath);
                        continue;
                    }

                    StreamerConfig streamerConfig = ConfigFetcher.getStreamerInfoByName(streamerName);
                    if (streamerConfig == null) {
                        log.warn("Streamer config not found for name: {}, skip deleting {}",
                                streamerName, curRecordPath);
                        continue;
                    }
                    List<String> uploadPlatforms = streamerConfig.getUploadPlatforms();
                    if (uploadPlatforms == null || uploadPlatforms.isEmpty()) {
                        log.info("No upload platforms configured for streamer: {}, skip deleting {}",
                                streamerName, curRecordPath);
                        continue;
                    }
                    FileStatusModel fileStatusModel = FileStatusModel.loadFromFile(curRecordPath);
                    if (fileStatusModel == null) {
                        log.warn("Failed to load fileStatus.json from: {}, skip deleting", curRecordPath);
                        continue;
                    }

                    List<String> unfinishedPlatforms = findUnfinishedPlatforms(
                            uploadPlatforms, fileStatusModel);
                    boolean allPost = unfinishedPlatforms.isEmpty();
                    long referenceTime = fileStatusModel.getEarliestPostFailureTime(
                            unfinishedPlatforms);
                    if (referenceTime <= 0) {
                        // 旧 fileStatus.json 没有失败时间。首次观察到未完成状态时开始计时，
                        // 避免升级后立即删除已有录像。
                        for (String platform : unfinishedPlatforms) {
                            fileStatusModel.failPost(platform, now);
                        }
                        fileStatusModel.writeSelfToFile(curRecordPath);
                        referenceTime = now;
                    }
                    long failedAgeMillis = Math.max(0L, now - referenceTime);
                    CleanupAction action = decideCleanupAction(allPost, failedAgeMillis,
                            threshold.keepHighlightMillis, threshold.deleteFailedMillis);

                    if (action == CleanupAction.DELETE_SUCCEEDED) {
                        log.info("All platforms uploaded, delete record directory: {}", curRecordPath);
                        FileUtils.deleteDirectory(recordDir);
                    } else if (action == CleanupAction.KEEP_HIGHLIGHT_ONLY) {
                        keepHighlightAndStatusOnly(recordDir);
                        log.warn("Upload has been incomplete for over {} hours; only {} is retained, "
                                        + "path: {}, unfinished platforms: {}",
                                threshold.keepHighlightHours, RecordConstant.HL_VIDEO,
                                curRecordPath, unfinishedPlatforms);
                    } else if (action == CleanupAction.DELETE_FAILED) {
                        warnBeforeDeletingFailedDirectory(msgSendService, streamerName,
                                curRecordPath, unfinishedPlatforms, threshold.deleteFailedHours);
                        log.warn("Delete upload-failed directory after {} hours: {}",
                                threshold.deleteFailedHours, curRecordPath);
                        FileUtils.deleteDirectory(recordDir);
                    }
                } catch (Exception e) {
                    log.error("Failed to clean record path: {}", curRecordPath, e);
                }
            }
        }
    }

    private static List<File> listRecordDir() {
        Environment environment = SpringUtil.getBean(Environment.class);
        String videoSavePath = environment.getProperty("sh.video-save.path");
        File dir = new File(videoSavePath);
        List<File> res = Lists.newArrayList();
        File[] files = dir.listFiles();
        if (files == null) {
            log.warn("Video save path is unavailable or empty: {}", videoSavePath);
            return res;
        }
        for (File file : files) {
            if (file.isDirectory()) {
                res.add(file);
            }
        }
        return res;
    }

    private static List<String> findUnfinishedPlatforms(List<String> uploadPlatforms,
                                                        FileStatusModel fileStatusModel) {
        List<String> unfinishedPlatforms = new ArrayList<>();
        for (String platform : uploadPlatforms) {
            if (!fileStatusModel.isFinishPost(platform)) {
                unfinishedPlatforms.add(platform);
            }
        }
        return unfinishedPlatforms;
    }

    static CleanupAction decideCleanupAction(boolean allPost,
                                             long failedAgeMillis,
                                             long keepHighlightMillis,
                                             long deleteFailedMillis) {
        if (allPost) {
            return CleanupAction.DELETE_SUCCEEDED;
        }
        if (failedAgeMillis >= deleteFailedMillis) {
            return CleanupAction.DELETE_FAILED;
        }
        if (failedAgeMillis >= keepHighlightMillis) {
            return CleanupAction.KEEP_HIGHLIGHT_ONLY;
        }
        return CleanupAction.KEEP_ALL;
    }

    static void keepHighlightAndStatusOnly(File recordDir) throws IOException {
        File[] children = recordDir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            String name = child.getName();
            if (child.isFile() && (RecordConstant.HL_VIDEO.equals(name)
                    || STATUS_FILE_NAME.equals(name))) {
                continue;
            }
            if (child.isDirectory()) {
                FileUtils.deleteDirectory(child);
            } else {
                FileUtils.forceDelete(child);
            }
        }
    }

    private static CleanupThreshold resolveThreshold(InitConfig initConfig) {
        int keepHours = positiveOrDefault(initConfig == null
                        ? null : initConfig.getFailedUploadKeepHighlightHours(),
                DEFAULT_KEEP_HIGHLIGHT_HOURS);
        int deleteHours = positiveOrDefault(initConfig == null
                        ? null : initConfig.getFailedUploadDeleteHours(),
                DEFAULT_DELETE_FAILED_HOURS);
        if (deleteHours <= keepHours) {
            log.warn("failedUploadDeleteHours ({}) must be greater than "
                    + "failedUploadKeepHighlightHours ({}); use {} hours instead",
                    deleteHours, keepHours, keepHours + 1);
            deleteHours = keepHours + 1;
        }
        return new CleanupThreshold(keepHours, deleteHours);
    }

    private static int positiveOrDefault(Integer value, int defaultValue) {
        return value == null || value <= 0 ? defaultValue : value;
    }

    private static void warnBeforeDeletingFailedDirectory(MsgSendService msgSendService,
                                                           String streamerName,
                                                           String recordPath,
                                                           List<String> unfinishedPlatforms,
                                                           int deleteHours) {
        String message = "上传失败已超过" + deleteHours + "小时，本地录像目录将被删除。"
                + "\n主播: " + streamerName
                + "\n目录: " + recordPath
                + "\n未完成平台: " + unfinishedPlatforms;
        try {
            msgSendService.sendText(message);
        } catch (Exception e) {
            // 告警服务故障不能阻止磁盘兜底清理。
            log.error("Failed to send upload-retention deletion warning, path: {}",
                    recordPath, e);
        }
    }

    enum CleanupAction {
        KEEP_ALL,
        KEEP_HIGHLIGHT_ONLY,
        DELETE_SUCCEEDED,
        DELETE_FAILED
    }

    private static class CleanupThreshold {
        private final int keepHighlightHours;
        private final int deleteFailedHours;
        private final long keepHighlightMillis;
        private final long deleteFailedMillis;

        private CleanupThreshold(int keepHighlightHours, int deleteFailedHours) {
            this.keepHighlightHours = keepHighlightHours;
            this.deleteFailedHours = deleteFailedHours;
            this.keepHighlightMillis = TimeUnit.HOURS.toMillis(keepHighlightHours);
            this.deleteFailedMillis = TimeUnit.HOURS.toMillis(deleteFailedHours);
        }
    }
}
