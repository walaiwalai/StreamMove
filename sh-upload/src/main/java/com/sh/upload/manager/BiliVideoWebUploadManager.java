package com.sh.upload.manager;

import cn.hutool.core.io.FileUtil;
import com.alibaba.fastjson.JSON;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.sh.config.exception.ErrorEnum;
import com.sh.config.exception.StreamerHelperException;
import com.sh.config.manager.ConfigManager;
import com.sh.config.model.config.StreamerInfo;
import com.sh.config.model.stauts.FileStatusModel;
import com.sh.config.model.video.*;
import com.sh.config.utils.MyFileUtil;
import com.sh.engine.manager.StatusManager;
import com.sh.engine.model.record.RecordTask;
import com.sh.upload.model.BiliPreUploadInfoModel;
import com.sh.upload.model.BiliPreUploadModel;
import com.sh.upload.model.BiliVideoUploadTask;
import com.sh.upload.model.web.BiliVideoUploadResultModel;
import com.sh.upload.service.BiliWorkUploadServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.FileFilterUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.StringSubstitutor;
import org.apache.http.entity.InputStreamEntity;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import static com.sh.upload.constant.UploadConstant.*;

/**
 * 上传视频文件
 *
 * @author caiWen
 * @date 2023/1/25 18:58
 */
@Component
@Slf4j
public class BiliVideoWebUploadManager {
    @Resource
    ConfigManager configManager;
    @Resource
    StatusManager statusManager;
    @Resource
    BiliWorkUploadServiceImpl biliVideoUploadService;

    private static final int UPLOAD_CORE_SIZE = 4;

    /**
     * 上传视频线程池
     */
    private static final ExecutorService UPLOAD_POOL = new ThreadPoolExecutor(
            4,
            4,
            600,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(120),
            new ThreadFactoryBuilder().setNameFormat("bili-upload-thread-%d").build(),
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    public static final Map<String, String> BILI_HEADERS = Maps.newHashMap();

    static {
        BILI_HEADERS.put("Connection", "alive");
        BILI_HEADERS.put("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        BILI_HEADERS.put("User-Agent",
                "Mozilla/5.0 (Linux; Android 6.0; Nexus 5 Build/MRA58N) AppleWebKit/537.36 (KHTML, like Gecko) "
                        + "Chrome/109.0.0.0 Mobile Safari/537.36 Edg/109.0.1518.55");
        BILI_HEADERS.put("Accept-Encoding", "gzip,deflate");
    }


    public static boolean isUploadPoolAllWork() {
        return ((ThreadPoolExecutor) UPLOAD_POOL).getActiveCount() >= UPLOAD_CORE_SIZE;
    }

    public void upload(BiliVideoUploadTask uploadTaskModel) {
        if (!checkNeedUpload(uploadTaskModel)) {
            return;
        }

        String dirName = uploadTaskModel.getDirName();
        try {
            log.info("begin upload, dirName: {}", dirName);
            // 1. 锁住上传视频
            statusManager.lockRecordForSubmission(dirName);

            // 2.加载文件夹状态并注入
            insertValueFromFileStatus(uploadTaskModel);

            // 3. 获取本地视频文件
            log.info("get local videos, path: {}.", dirName);
            List<LocalVideo> localVideos = fetchLocalVideos(dirName, uploadTaskModel);
            if (CollectionUtils.isEmpty(localVideos)) {
                log.warn("{} has no videos", dirName);
                return;
            }

            // 4.上传视频
            log.info("Start to upload videoParts ...");
            List<RemoteSeverVideo> remoteSeverVideos = uploadVideoParts(localVideos, uploadTaskModel);
            log.info("Upload videoParts END, remoteVideos: {}", JSON.toJSONString(remoteSeverVideos));

            // 6.更新文件属性
            FileStatusModel.updateToFile(dirName, FileStatusModel.builder().isPost(true).build());
        } catch (Exception e) {
            log.error("Upload video fail, dirName: {}", dirName, e);
        } finally {
            statusManager.releaseRecordForSubmission(dirName);
        }
    }

    private void insertValueFromFileStatus(BiliVideoUploadTask uploadModel) {
        File statusFile = new File(uploadModel.getDirName(), "fileStatus.json");
        if (!statusFile.exists()) {
            return;
        }
        // 1.加载fileStatus.json文件
        FileStatusModel fileStatusModel = FileStatusModel.loadFromFile(uploadModel.getDirName());

        // 2. 注入配置
        UploadVideoPair videoParts = fileStatusModel.getVideoParts();
        uploadModel.setSucceedUploaded(Optional.ofNullable(videoParts).map(UploadVideoPair::getSucceedUploadedVideos)
                .orElse(Lists.newArrayList()));
        uploadModel.setIsUploadFail(fileStatusModel.getIsFailed());

        if (BooleanUtils.isTrue(fileStatusModel.getIsFailed())) {
            uploadModel.setFailUpload(Optional.ofNullable(videoParts)
                    .map(UploadVideoPair::getFailedUploadVideo).orElse(null));
        }
    }

    private List<LocalVideo> fetchLocalVideos(String dirName, BiliVideoUploadTask uploadModel) {
        long videoPartLimitSize = Optional.ofNullable(configManager.getStreamHelperConfig().getVideoPartLimitSize())
                .orElse(100) * 1024L * 1024L;
        Integer videoIndex = 0;

        StreamerInfo streamerInfo = configManager.getStreamerInfoByName(uploadModel.getStreamerName());

        // 遍历本地的视频文件
        Collection<File> files = FileUtils.listFiles(new File(dirName), FileFilterUtils.suffixFileFilter("mp4"), null);
        List<File> sortedFile = MyFileUtil.getFileSort(Lists.newArrayList(files));
        List<LocalVideo> localVideos = Lists.newArrayList();
        for (File subVideoFile : sortedFile) {
            videoIndex++;
            String fullPath = subVideoFile.getAbsolutePath();
            // 过小的视频文件不上场
            long fileSize = FileUtil.size(subVideoFile);
            if (fileSize < videoPartLimitSize) {
                log.info("video size too small, give up upload, fileName: {}, size: {}, limitSize: {}",
                        subVideoFile.getName(), fileSize, videoPartLimitSize);
                continue;
            }

            // 已经上传的文件不重复上传
            if (CollectionUtils.isNotEmpty(uploadModel.getSucceedUploaded())) {
                List<String> succeedPaths = uploadModel.getSucceedUploaded().stream().map(
                        SucceedUploadSeverVideo::getLocalFileFullPath).collect(Collectors.toList());
                if (succeedPaths.contains(fullPath)) {
                    log.info("video has been uploaded, path: {}", fullPath);
                    continue;
                }
            }


            if (uploadModel.getIsUploadFail()) {
                // 处理上传失败的视频
                FailedUploadVideo failedUploadVideo = uploadModel.getFailUpload();
                boolean isCurFailedPart = Objects.equals(Optional.ofNullable(failedUploadVideo)
                        .map(FailedUploadVideo::getLocalFileFullPath)
                        .orElse(null), fullPath);
                if (isCurFailedPart) {
                    log.info("push upload error video to videoParts");
                    localVideos.add(LocalVideo.builder()
                            .isFailed(true)
                            .localFileFullPath(Optional.ofNullable(uploadModel.getFailUpload())
                                    .map(FailedUploadVideo::getLocalFileFullPath)
                                    .orElse(null))
                            .title(uploadModel.getTitle() + " [P" + (videoIndex + 1) + "]")
                            .desc(streamerInfo.getDesc())
                            .fileSize(fileSize)
                            .build());

                }
            } else {
                localVideos.add(LocalVideo.builder()
                        .isFailed(false)
                        .localFileFullPath(fullPath)
                        .title(uploadModel.getTitle() + " [P" + (videoIndex + 1) + "]")
                        .desc(streamerInfo.getDesc())
                        .fileSize(fileSize)
                        .build());
            }
        }
        log.info("Final videoParts: {}", JSON.toJSONString(localVideos));
        return localVideos;
    }

    /**
     * 上传分段视频
     *
     * @param localVideos
     * @param uploadModel
     * @return
     */
    private List<RemoteSeverVideo> uploadVideoParts(List<LocalVideo> localVideos, BiliVideoUploadTask uploadModel)
            throws Exception {
        List<RemoteSeverVideo> remoteVideos = Lists.newArrayList();
        for (LocalVideo localVideo : localVideos) {
            BiliVideoUploadResultModel biliVideoUploadResult;
            try {
                // 1. 上传视频
                biliVideoUploadResult = uploadChunksForNewVideo(uploadModel, localVideo);
                if (CollectionUtils.isNotEmpty(biliVideoUploadResult.getFailedChunks()) || !biliVideoUploadResult
                        .isFinishChunksUpload() || biliVideoUploadResult.getRemoteSeverVideo() == null) {
                    // 上传chunks失败，完成上传失败，发送作品为空均视为失败
                    localVideo.setFailed(true);
                    syncStatus(uploadModel.getDirName(), localVideo, biliVideoUploadResult);
                    throw new StreamerHelperException(ErrorEnum.UPLOAD_CHUNK_ERROR);
                }

                remoteVideos.add(biliVideoUploadResult.getRemoteSeverVideo());

                // 同步最新状态到fileStatus.json
                syncStatus(uploadModel.getDirName(), localVideo, biliVideoUploadResult);
            } catch (Exception e) {
                log.error("upload video part fail, localVideoPart: {}", localVideo.getLocalFileFullPath(), e);
                statusManager.releaseRecordForSubmission(uploadModel.getDirName());
                throw e;
            }
        }
        return remoteVideos;
    }


    /**
     * 更新状态到fileStatus.json
     *
     * @param dirName
     * @param localVideo
     * @param biliVideoUploadResult
     */
    private void syncStatus(String dirName, LocalVideo localVideo, BiliVideoUploadResultModel biliVideoUploadResult) {
        FileStatusModel fileStatusModel = FileStatusModel.loadFromFile(dirName);
        UploadVideoPair videoPair = Optional.ofNullable(fileStatusModel.getVideoParts()).orElse(new UploadVideoPair());
        List<SucceedUploadSeverVideo> exsitedSuccessUploadVideoParts = Optional.ofNullable(
                videoPair.getSucceedUploadedVideos())
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

        if (localVideo.isFailed()) {
            // 说明该localVideo上传失败了
            failedUploadVideo.setLocalFileFullPath(localVideo.getLocalFileFullPath());
            failedUploadVideo.setFailUploadVideoChunks(biliVideoUploadResult.getFailedChunks());
            videoPair.setFailedUploadVideo(failedUploadVideo);
        } else {
            // 说明该localVideo上传成功了
            SucceedUploadSeverVideo newSucceedPart = new SucceedUploadSeverVideo();
            newSucceedPart.setLocalFileFullPath(localVideo.getLocalFileFullPath());
            newSucceedPart.setFilename(biliVideoUploadResult.getRemoteSeverVideo().getFilename());
            newSucceedPart.setDesc(biliVideoUploadResult.getRemoteSeverVideo().getDesc());
            newSucceedPart.setTitle(biliVideoUploadResult.getRemoteSeverVideo().getTitle());
            exsitedSuccessUploadVideoParts.add(newSucceedPart);
            videoPair.setSucceedUploadedVideos(exsitedSuccessUploadVideoParts);
        }
        fileStatusModel.setVideoParts(videoPair);

        FileStatusModel.updateToFile(dirName, fileStatusModel);
    }


    public BiliVideoUploadResultModel uploadChunksForNewVideo(BiliVideoUploadTask uploadModel, LocalVideo localVideo)
            throws Exception {
        File videoFile = new File(localVideo.getLocalFileFullPath());
        String videoName = videoFile.getName();
        long fileSize = localVideo.getFileSize();
        BiliVideoUploadResultModel uploadResult = new BiliVideoUploadResultModel();

        // 1.获得预加载上传的b站视频地址信息
        String biliCookies = configManager.getUploadPersonInfo().getBiliCookies();
        BiliPreUploadModel biliPreUploadModel = new BiliPreUploadModel(videoName, fileSize, biliCookies);
        if (StringUtils.isBlank(biliPreUploadModel.getUploadId())) {
            log.error("video preUpload info fetch error, videoName: {}", videoFile);
            throw new StreamerHelperException(ErrorEnum.UPLOAD_CHUNK_ERROR);
        }
        BiliPreUploadInfoModel biliPreUploadInfo = biliPreUploadModel.getBiliPreUploadVideoInfo();
        Map<String, String> extension = buildExtension(localVideo, biliPreUploadModel);

        // 2.进行视频分块上传
        Integer chunkSize = biliPreUploadInfo.getChunkSize();
        int partCount = (int) Math.ceil(fileSize * 1.0 / chunkSize);
        log.info("video size is {}M, seg {} parts to upload.", fileSize / 1024 / 1024, partCount);
        CountDownLatch countDownLatch = new CountDownLatch(partCount);
        List<FailUploadVideoChunk> failUploadVideoChunks = Lists.newCopyOnWriteArrayList();
        for (int i = 0; i < partCount; i++) {
            //当前分段起始位置
            long curChunkStart = i * chunkSize;
            // 当前分段大小  如果为最后一个大小为fileSize-curChunkStart  其他为partSize
            long curChunkSize = (i + 1 == partCount) ? (fileSize - curChunkStart) : chunkSize;
            long curChunkEnd = curChunkStart + curChunkSize;

            FileInputStream fis = new FileInputStream(videoFile);
            fis.skip(curChunkStart);
            int finalI = i;

            CompletableFuture.supplyAsync(() -> {
                return biliVideoUploadService.uploadChunk(
                        new InputStreamEntity(fis, curChunkSize), finalI, partCount, curChunkSize, curChunkStart,
                        curChunkEnd, fileSize, extension);
            }, UPLOAD_POOL)
                    .whenComplete((isSuccess, throwbale) -> {
                        if (!isSuccess) {
                            FailUploadVideoChunk failUploadVideoChunk = new FailUploadVideoChunk();
                            failUploadVideoChunk.setChunkStart(curChunkStart);
                            failUploadVideoChunk.setCurChunkSize(curChunkSize);
                            failUploadVideoChunk.setChunkNo(finalI);
                            failUploadVideoChunks.add(failUploadVideoChunk);
                        }
                        countDownLatch.countDown();
                    });
        }

        countDownLatch.await(1, TimeUnit.HOURS);


        if (CollectionUtils.isEmpty(failUploadVideoChunks)) {
            log.info("video chunks upload success, videoPath: {}", localVideo.getLocalFileFullPath());
        } else {
            log.error("video chunks upload fail, failed chunkNos: {}", failUploadVideoChunks.stream().map(
                    FailUploadVideoChunk::getChunkNo).collect(Collectors.toList()));
            uploadResult.setFailedChunks(failUploadVideoChunks);
            return uploadResult;
        }

        // 3. 完成分块的上传
        boolean isFinish = biliVideoUploadService.finishChunksUpload(localVideo.getTitle(), partCount, extension);
        uploadResult.setFinishChunksUpload(isFinish);
        if (isFinish) {
            log.info("video finish upload success, videoName: {}", videoName);
        } else {
            log.error("video finish upload fail, videoName: {}", videoName);
            return uploadResult;
        }

        // 4. 组装服务器端的视频
        String uposUri = biliPreUploadInfo.getUposUri();
        String[] tmps = uposUri.split("//")[1].split("/");
        String fileNameOnServer = tmps[tmps.length - 1].split(".mp4")[0];
        RemoteSeverVideo remoteSeverVideo = new RemoteSeverVideo(localVideo.getTitle(), localVideo.getDesc(),
                fileNameOnServer);
        boolean isPostSuccess = biliVideoUploadService.postWork(uploadModel.getStreamerName(),
                Lists.newArrayList(remoteSeverVideo), extension);
        if (isPostSuccess) {
            uploadResult.setRemoteSeverVideo(remoteSeverVideo);
        }
        return uploadResult;
    }

    private Map<String, String> buildExtension(LocalVideo localVideo, BiliPreUploadModel biliPreUploadModel) {
        Map<String, String> extension = Maps.newHashMap();
        extension.put(BILI_UPLOAD_URL, biliPreUploadModel.getUploadUrl());
        extension.put(BILI_UPLOAD_ID, biliPreUploadModel.getUploadId());
        extension.put(BILI_UPOS_URI, biliPreUploadModel.getBiliPreUploadVideoInfo().getUposUri());
        extension.put(BILI_UPOS_AUTH, biliPreUploadModel.getBiliPreUploadVideoInfo().getAuth());
        extension.put(BILI_VIDEO_TILE, localVideo.getTitle());
        return extension;
    }


    public boolean checkNeedUpload(BiliVideoUploadTask uploadModel) {
        // 不上传本地直接返回
        String recorderName = uploadModel.getStreamerName();
        Boolean uploadLocalFile = configManager.getStreamerInfoByName(recorderName).getUploadLocalFile();
        if (BooleanUtils.isNotTrue(uploadLocalFile)) {
            log.info("user config => {} uploadLocalFile {}. Upload Give up...", recorderName, uploadLocalFile);
            return false;
        }

        // 没有对应文件夹直接返回
        if (StringUtils.isBlank(uploadModel.getDirName())) {
            log.error("filePath not existed for: {}.", recorderName);
            return false;
        }

        // 当前视频任务是否在上传中
        if (statusManager.isRecordOnSubmission(uploadModel.getDirName())) {
            log.error("videos in {} is on submission, do upload repeat", uploadModel.getDirName());
            return false;
        }
        return true;
    }


//    private void syncUserInfo(BiliUploadUser uploadUser) {
//        configManager.syncUploadPersonInfoToConfig(
//                UploadPersonInfo.builder()
//                        .accessToken(uploadUser.getAccessToken())
//                        .mid(uploadUser.getMid())
//                        .refreshToken(uploadUser.getRefreshToken())
//                        .expiresIn(uploadUser.getExpiresIn())
//                        .nickname(uploadUser.getNickName())
//                        .tokenSignDate(uploadUser.getTokenSignTime())
//                        .build()
//        );
//    }

    public BiliVideoUploadTask convertToUploadModel(RecordTask recordTask) {
        return BiliVideoUploadTask.builder()
                .streamerName(recordTask.getRecorderName())
                .dirName(recordTask.getDirName())
                .title(genVideoTitle(recordTask))
                .succeedUploaded(Lists.newArrayList())
                .build();

    }
    private String genVideoTitle(RecordTask recordTask) {
        Map<String, String> paramsMap = Maps.newHashMap();
        paramsMap.put("time", recordTask.getTimeV());
        paramsMap.put("name", recordTask.getRecorderName());

        StreamerInfo streamerInfo = configManager.getStreamerInfoByName(recordTask.getRecorderName());
        if (StringUtils.isNotBlank(streamerInfo.getTemplateTitle())) {
            StringSubstitutor sub = new StringSubstitutor(paramsMap);
            return sub.replace(streamerInfo.getTemplateTitle());
        } else {
            return recordTask.getRecorderName() + " " + recordTask.getTimeV() + " " + "录播";
        }
    }
}
