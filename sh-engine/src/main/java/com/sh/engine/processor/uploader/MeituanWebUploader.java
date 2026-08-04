package com.sh.engine.processor.uploader;

import com.sh.config.manager.ConfigFetcher;
import com.sh.config.model.config.StreamerConfig;
import com.sh.engine.constant.RecordConstant;
import com.sh.engine.constant.UploadPlatformEnum;
import com.sh.engine.model.StreamerInfoHolder;
import com.sh.engine.model.ffmpeg.ScreenshotCmd;
import com.sh.engine.model.video.RemoteSeverVideo;
import com.sh.engine.processor.uploader.meituan.MeituanWebUploadClient;
import com.sh.engine.processor.uploader.meta.MeituanWorkMetaData;
import com.sh.message.service.MsgSendService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.File;

/** Uploads highlight.mp4 to Meituan Creator using Java HTTP and saved web login state. */
@Slf4j
@Component
public class MeituanWebUploader extends Uploader {
    @Resource
    private MsgSendService msgSendService;

    @Override
    public String getType() {
        return UploadPlatformEnum.MEI_TUAN_VIDEO.getType();
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
            throw new IllegalStateException("无法创建美团账号目录: "
                    + (accountDir == null ? "null" : accountDir.getAbsolutePath()));
        }
    }

    @Override
    public boolean upload(String recordPath) throws Exception {
        File videoFile = new File(recordPath, RecordConstant.HL_VIDEO);
        if (!videoFile.isFile()) {
            log.info("highlight video does not exist, skip meituan web upload: {}",
                    videoFile.getAbsolutePath());
            return true;
        }
        if (getUploadedVideo(recordPath, videoFile) != null) {
            log.info("highlight video has already been uploaded to meituan, skip: {}",
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
        MeituanWorkMetaData metadata = (MeituanWorkMetaData) new UploaderFactory.MeituanMetaDataBuilder()
                .buildMetaData(streamerConfig, recordPath);
        File coverFile = extractFirstFrameCover(recordPath, videoFile);

        log.info("begin meituan Java HTTP upload, video: {}, cover: {}",
                videoFile.getAbsolutePath(), coverFile.getAbsolutePath());
        try (MeituanWebUploadClient client = new MeituanWebUploadClient(
                getAccoutFile(), msgSendService)) {
            MeituanWebUploadClient.UploadResult result = client.upload(videoFile, coverFile,
                    metadata);
            saveUploadedVideo(recordPath, new RemoteSeverVideo(result.getContentId(),
                    videoFile.getAbsolutePath()));
            log.info("meituan Java HTTP upload success, video: {}, contentId: {}, videoKey: {}",
                    videoFile.getAbsolutePath(), result.getContentId(), result.getVideoKey());
        }
        return true;
    }

    private File extractFirstFrameCover(String recordPath, File videoFile) {
        File coverDir = new File(recordPath, getType() + "-cover");
        if (!coverDir.exists() && !coverDir.mkdirs()) {
            throw new IllegalStateException("无法创建美团首帧封面目录: "
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
