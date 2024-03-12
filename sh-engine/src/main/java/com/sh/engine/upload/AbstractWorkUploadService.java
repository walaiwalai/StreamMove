package com.sh.engine.upload;

import com.google.common.collect.Lists;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.sh.config.model.stauts.FileStatusModel;
import com.sh.config.model.video.FailedUploadVideo;
import com.sh.config.model.video.LocalVideo;
import com.sh.config.model.video.SucceedUploadSeverVideo;
import com.sh.config.model.video.UploadVideoPair;
import com.sh.engine.model.bili.web.VideoUploadResultModel;
import com.sh.engine.model.upload.BaseUploadTask;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * @Author caiwen
 * @Date 2024 03 10 12 18
 **/
@Slf4j
public abstract class AbstractWorkUploadService {
    protected static final ExecutorService UPLOAD_POOL = new ThreadPoolExecutor(
            4,
            4,
            600,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(40960),
            new ThreadFactoryBuilder().setNameFormat("upload-thread-%d").build(),
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    public abstract String getName();

    public abstract boolean upload(List<LocalVideo> localVideos, BaseUploadTask task) throws Exception;


    protected void syncStatus(String dirName, LocalVideo localVideo, VideoUploadResultModel uploadResult) {
        String platName = getName();

        FileStatusModel fileStatusModel = FileStatusModel.loadFromFile(dirName);
        UploadVideoPair videoPair = Optional.ofNullable(fileStatusModel.fetchVideoPartByPlatform(platName))
                .orElse(new UploadVideoPair());

        List<SucceedUploadSeverVideo> exsitedSuccessUploadVideoParts = Optional.ofNullable(videoPair.getSucceedUploadedVideos())
                .orElse(Lists.newArrayList());
        FailedUploadVideo failedUploadVideo = Optional.ofNullable(videoPair.getFailedUploadVideo())
                .orElse(new FailedUploadVideo());

        boolean isAlreadyInStatus = exsitedSuccessUploadVideoParts.stream().anyMatch(
                succeedUploadVideo -> StringUtils.equals(succeedUploadVideo.getLocalFileFullPath(),
                        localVideo.getLocalFileFullPath()));
        if (isAlreadyInStatus) {
            log.info("Found Exist Video {}", localVideo.getLocalFileFullPath());
            return;
        }

        if (localVideo.isUpload()) {
            // 说明该localVideo上传成功了
            SucceedUploadSeverVideo newSucceedPart = new SucceedUploadSeverVideo();
            newSucceedPart.setLocalFileFullPath(localVideo.getLocalFileFullPath());
            newSucceedPart.setFilename(uploadResult.getRemoteSeverVideo().getFilename());
            newSucceedPart.setTitle(uploadResult.getRemoteSeverVideo().getTitle());
            exsitedSuccessUploadVideoParts.add(newSucceedPart);
            videoPair.setSucceedUploadedVideos(exsitedSuccessUploadVideoParts);
        } else {
            // 说明该localVideo上传失败了
            failedUploadVideo.setLocalFileFullPath(localVideo.getLocalFileFullPath());
            failedUploadVideo.setFailUploadVideoChunks(uploadResult.getFailedChunks());
            videoPair.setFailedUploadVideo(failedUploadVideo);
        }
        fileStatusModel.updateVideoPartByPlatform(platName, videoPair);

        FileStatusModel.updateToFile(dirName, fileStatusModel);
    }
}
