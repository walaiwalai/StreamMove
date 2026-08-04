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
import com.sh.message.service.MsgSendService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.File;

/** Uploads highlight.mp4 with Java HTTP media transfer and the Creator page security context. */
@Slf4j
@Component
public class DouyinWebUploader extends Uploader {
    @Resource
    private MsgSendService msgSendService;

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
        File accountDir = accountFile.getAbsoluteFile().getParentFile();
        if (accountDir == null || (!accountDir.isDirectory() && !accountDir.mkdirs())) {
            throw new IllegalStateException("无法创建抖音账号目录: "
                    + (accountDir == null ? "null" : accountDir.getAbsolutePath()));
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
        File coverFile = extractFirstFrameCover(recordPath, videoFile);

        log.info("begin douyin web HTTP upload, video: {}, cover: {}",
                videoFile.getAbsolutePath(), coverFile.getAbsolutePath());
        try (DouyinWebUploadClient client = new DouyinWebUploadClient(
                getAccoutFile(), msgSendService)) {
            DouyinWebUploadClient.UploadResult result = client.upload(videoFile, coverFile, metadata);
            saveUploadedVideo(recordPath, new RemoteSeverVideo(result.getItemId(),
                    videoFile.getAbsolutePath()));
            log.info("douyin web HTTP upload success, video: {}, itemId: {}, videoId: {}",
                    videoFile.getAbsolutePath(), result.getItemId(), result.getVideoId());
        }
        return true;
    }

    private File extractFirstFrameCover(String recordPath, File videoFile) {
        File coverDir = new File(recordPath, getType() + "-cover");
        if (!coverDir.exists() && !coverDir.mkdirs()) {
            throw new IllegalStateException("无法创建抖音封面目录: " + coverDir.getAbsolutePath());
        }
        String videoName = videoFile.getName();
        int extensionIndex = videoName.lastIndexOf('.');
        String videoPrefix = extensionIndex > 0 ? videoName.substring(0, extensionIndex) : videoName;
        File firstFrame = new File(coverDir, videoPrefix + "#1.jpg");

        ScreenshotCmd screenshot = new ScreenshotCmd(videoFile, coverDir, 0, 1,
                "scale=trunc(iw/2)*2:trunc(ih/2)*2", 1, 1, true);
        screenshot.execute(120);
        if (!screenshot.isNormalExit() || !firstFrame.isFile()) {
            throw new IllegalStateException("从精彩视频提取第一帧封面失败: " + videoFile.getAbsolutePath());
        }
        return firstFrame;
    }
}
