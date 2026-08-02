package com.sh.engine.processor.uploader;

import com.sh.config.manager.ConfigFetcher;
import com.sh.config.model.config.StreamerConfig;
import com.sh.engine.constant.RecordConstant;
import com.sh.engine.constant.UploadPlatformEnum;
import com.sh.engine.model.StreamerInfoHolder;
import com.sh.engine.model.ffmpeg.ScreenshotCmd;
import com.sh.engine.model.video.RemoteSeverVideo;
import com.sh.engine.processor.uploader.meta.WechatVideoMetaData;
import com.sh.engine.processor.uploader.wechat.WechatVideoWebUploadClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;

/** Uploads highlight.mp4 to WeChat Channels using Java HTTP and the saved web login state. */
@Slf4j
@Component
public class WechatVideoWebUploader extends Uploader {
    @Override
    public String getType() {
        return UploadPlatformEnum.WECHAT_VIDEO_WEB.getType();
    }

    @Override
    public int getMaxUploadParallel() {
        return 1;
    }

    @Override
    public void initUploader() {
        File accountFile = getAccoutFile();
        if (!accountFile.isFile()) {
            throw new IllegalStateException("微信视频号登录态文件不存在，请扫码登录后保存到: "
                    + accountFile.getAbsolutePath());
        }
    }

    @Override
    public boolean upload(String recordPath) throws Exception {
        File videoFile = new File(recordPath, RecordConstant.HL_VIDEO);
        if (!videoFile.isFile()) {
            log.info("highlight video does not exist, skip wechat channels web upload: {}",
                    videoFile.getAbsolutePath());
            return true;
        }
        if (getUploadedVideo(recordPath, videoFile) != null) {
            log.info("highlight video has already been uploaded to wechat channels, skip: {}",
                    videoFile.getAbsolutePath());
            return true;
        }

        initUploader();
        StreamerConfig streamerConfig = ConfigFetcher.getStreamerInfoByName(
                StreamerInfoHolder.getCurStreamerName());
        if (streamerConfig == null) {
            throw new IllegalStateException("找不到当前主播配置: "
                    + StreamerInfoHolder.getCurStreamerName());
        }
        WechatVideoMetaData metadata = (WechatVideoMetaData) new UploaderFactory.WechatMetaDataBuilder()
                .buildMetaData(streamerConfig, recordPath);
        File coverFile = extractFirstFrameCover(recordPath, videoFile);

        log.info("begin wechat channels Java HTTP upload, video: {}, cover: {}",
                videoFile.getAbsolutePath(), coverFile.getAbsolutePath());
        try (WechatVideoWebUploadClient client = new WechatVideoWebUploadClient(getAccoutFile())) {
            WechatVideoWebUploadClient.UploadResult result = client.upload(videoFile, coverFile,
                    metadata);
            saveUploadedVideo(recordPath, new RemoteSeverVideo(result.getClientId(),
                    videoFile.getAbsolutePath()));
            log.info("wechat channels upload success, video: {}, clientId: {}, draftId: {}",
                    videoFile.getAbsolutePath(), result.getClientId(), result.getDraftId());
        }
        return true;
    }

    private File extractFirstFrameCover(String recordPath, File videoFile) {
        File coverDir = new File(recordPath, getType() + "-cover");
        if (!coverDir.exists() && !coverDir.mkdirs()) {
            throw new IllegalStateException("无法创建微信视频号首帧封面目录: "
                    + coverDir.getAbsolutePath());
        }
        String videoName = videoFile.getName();
        int extensionIndex = videoName.lastIndexOf('.');
        String videoPrefix = extensionIndex > 0
                ? videoName.substring(0, extensionIndex) : videoName;
        File firstFrame = new File(coverDir, videoPrefix + "#1.jpg");
        ScreenshotCmd screenshot = new ScreenshotCmd(videoFile, coverDir, 0, 1,
                "scale=trunc(iw/2)*2:trunc(ih/2)*2", 1, 1, true);
        screenshot.execute(120);
        if (!screenshot.isNormalExit() || !firstFrame.isFile()) {
            throw new IllegalStateException("从精彩视频提取第一帧封面失败: "
                    + videoFile.getAbsolutePath());
        }
        return firstFrame;
    }
}
