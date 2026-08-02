package com.sh.engine.processor.uploader;

import com.sh.config.manager.ConfigFetcher;
import com.sh.config.model.config.StreamerConfig;
import com.sh.engine.constant.RecordConstant;
import com.sh.engine.constant.UploadPlatformEnum;
import com.sh.engine.model.StreamerInfoHolder;
import com.sh.engine.model.ffmpeg.ScreenshotCmd;
import com.sh.engine.model.video.RemoteSeverVideo;
import com.sh.engine.processor.uploader.douyin.DouyinWebUploadClient;
import com.sh.engine.processor.uploader.meta.DouyinWorkMetaData;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.List;

/** Uploads highlight.mp4 with Java HTTP media transfer and the Creator page security context. */
@Slf4j
@Component
public class DouyinWebUploader extends Uploader {
    @Override
    public String getType() {
        return UploadPlatformEnum.DOU_YIN_WEB.getType();
    }

    @Override
    public int getMaxUploadParallel() {
        return 1;
    }

    @Override
    public void initUploader() {
        File accountFile = getAccoutFile();
        if (!accountFile.isFile()) {
            throw new IllegalStateException("抖音登录态文件不存在，请扫码登录后保存到: "
                    + accountFile.getAbsolutePath());
        }
    }

    @Override
    public boolean upload(String recordPath) throws Exception {
        File videoFile = new File(recordPath, RecordConstant.HL_VIDEO);
        if (!videoFile.isFile()) {
            log.info("highlight video does not exist, skip douyin web upload: {}", videoFile.getAbsolutePath());
            return true;
        }

        RemoteSeverVideo uploaded = getUploadedVideo(recordPath, videoFile);
        if (uploaded != null) {
            log.info("highlight video has already been uploaded to douyin, skip: {}",
                    videoFile.getAbsolutePath());
            return true;
        }

        initUploader();
        StreamerConfig streamerConfig = ConfigFetcher.getStreamerInfoByName(
                StreamerInfoHolder.getCurStreamerName());
        if (streamerConfig == null) {
            throw new IllegalStateException("找不到当前主播配置: " + StreamerInfoHolder.getCurStreamerName());
        }
        DouyinWorkMetaData metadata = (DouyinWorkMetaData) new UploaderFactory.DouyinMetaDataBuilder()
                .buildMetaData(streamerConfig, recordPath);
        File coverFile = resolveCoverFile(recordPath, videoFile, metadata);

        log.info("begin douyin web HTTP upload, video: {}, cover: {}",
                videoFile.getAbsolutePath(), coverFile.getAbsolutePath());
        try (DouyinWebUploadClient client = new DouyinWebUploadClient(getAccoutFile())) {
            DouyinWebUploadClient.UploadResult result = client.upload(videoFile, coverFile, metadata);
            saveUploadedVideo(recordPath, new RemoteSeverVideo(result.getItemId(),
                    videoFile.getAbsolutePath()));
            log.info("douyin web HTTP upload success, video: {}, itemId: {}, videoId: {}",
                    videoFile.getAbsolutePath(), result.getItemId(), result.getVideoId());
        }
        return true;
    }

    private File resolveCoverFile(String recordPath,
                                  File videoFile,
                                  DouyinWorkMetaData metadata) {
        if (StringUtils.isNotBlank(metadata.getPreViewFilePath())) {
            File configured = new File(metadata.getPreViewFilePath());
            if (configured.isFile()) {
                return configured;
            }
            log.warn("configured douyin cover does not exist, will extract a frame: {}",
                    configured.getAbsolutePath());
        }

        File workThumbnail = new File(recordPath, RecordConstant.THUMBNAIL_FILE_NAME);
        if (workThumbnail.isFile()) {
            return workThumbnail;
        }

        File coverDir = new File(recordPath, getType() + "-cover");
        if (!coverDir.exists() && !coverDir.mkdirs()) {
            throw new IllegalStateException("无法创建抖音封面目录: " + coverDir.getAbsolutePath());
        }
        ScreenshotCmd screenshot = new ScreenshotCmd(videoFile, coverDir, 1, 1,
                "scale=trunc(iw/2)*2:trunc(ih/2)*2", 1, 1, false);
        screenshot.execute(120);
        List<File> snapshots = screenshot.getSnapshotFiles();
        if (CollectionUtils.isEmpty(snapshots) || !snapshots.get(0).isFile()) {
            throw new IllegalStateException("从精彩视频提取抖音封面失败: " + videoFile.getAbsolutePath());
        }
        return snapshots.get(0);
    }
}
