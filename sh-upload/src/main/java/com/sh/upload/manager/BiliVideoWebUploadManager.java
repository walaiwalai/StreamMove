package com.sh.upload.manager;

import cn.hutool.core.io.FileUtil;
import com.alibaba.fastjson.JSON;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.sh.config.exception.ErrorEnum;
import com.sh.config.exception.StreamerHelperException;
import com.sh.config.manager.ConfigManager;
import com.sh.config.model.config.StreamerInfo;
import com.sh.config.model.config.UploadPersonInfo;
import com.sh.config.model.stauts.FileStatusModel;
import com.sh.config.model.video.*;
import com.sh.engine.manager.StatusManager;
import com.sh.engine.model.record.RecordTask;
import com.sh.upload.constant.UploadConstant;
import com.sh.upload.model.BiliPreUploadInfoModel;
import com.sh.upload.model.BiliVideoUploadTask;
import com.sh.upload.model.user.BiliUploadUser;
import com.sh.upload.model.web.BiliVideoUploadResultModel;
import com.sh.upload.service.BiliWorkUploadServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.StringSubstitutor;
import org.apache.http.entity.InputStreamEntity;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.*;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
    /**
     * 视频上传分块大小为5M
     */
    private static final int CHUNK_SIZE = 1024 * 1024 * 5;

    /**
     * 录播线程池
     */
    private final ExecutorService UPLOAD_POOL = new ThreadPoolExecutor(
            3, 3, 600, TimeUnit.SECONDS, new ArrayBlockingQueue<>(120),
            Executors.defaultThreadFactory(), new ThreadPoolExecutor.CallerRunsPolicy()
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


    public void upload(BiliVideoUploadTask uploadModel) {
        if (!checkNeedUpload(uploadModel)) {
            return;
        }

        String dirName = uploadModel.getDirName();
        try {
            log.info("begin upload, dirName: {}", dirName);
            // 1. 锁住上传视频
            statusManager.lockRecordForSubmission(dirName);

            // 2.加载文件夹状态并注入
            insertValueFromFileStatus(uploadModel);

            // 3. 获取本地视频文件
            log.info("get local videos, path: {}", dirName);
            List<LocalVideo> localVideos = fetchLocalVideos(dirName, uploadModel);
            if (CollectionUtils.isEmpty(localVideos)) {
                log.warn("{} has no videos", dirName);
                return;
            }

            // 4.上传视频
            log.info("Start to upload videoParts ...");
            List<RemoteSeverVideo> remoteSeverVideos = uploadVideoParts(localVideos, uploadModel);
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
            uploadModel.setDeadline(Optional.ofNullable(videoParts)
                    .map(UploadVideoPair::getFailedUploadVideo)
                    .map(FailedUploadVideo::getDeadline)
                    .orElse(0L)
            );
        }
    }

    private List<LocalVideo> fetchLocalVideos(String dirName, BiliVideoUploadTask uploadModel) {
        long videoPartLimitSize = uploadModel.getVideoPartLimitSizeInput() * 1024L * 1024L;
        Integer videoIndex = 0;

        List<String> subFileNames = FileUtil.listFileNames(dirName);
        List<LocalVideo> localVideos = Lists.newArrayList();
        for (String subFileName : subFileNames) {
            if (StringUtils.equals("fileStatus.json", subFileName)) {
                // 只处理视频文件
                continue;
            }

            File subVideoFile = new File(dirName, subFileName);
            String fullPath = subVideoFile.getAbsolutePath();
            // 过小的视频文件不上场
            long fileSize = FileUtil.size(subVideoFile);
            if (fileSize < videoPartLimitSize) {
                log.info("video size too small, give up upload, fileName: {}, size: {}, limitSize: {}", subFileName,
                        fileSize, videoPartLimitSize);
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
                Long failDeadLine = Optional.ofNullable(failedUploadVideo).map(FailedUploadVideo::getDeadline)
                        .orElse(0L);
                boolean shouldReUploadFailVideo = isCurFailedPart && (failDeadLine > System.currentTimeMillis());
                if (shouldReUploadFailVideo) {
                    log.info("push upload error video to videoParts");
                    localVideos.add(LocalVideo.builder()
                            .isFailed(true)
                            .localFileFullPath(Optional.ofNullable(uploadModel.getFailUpload())
                                    .map(FailedUploadVideo::getLocalFileFullPath)
                                    .orElse(null))
                            .title("P" + (videoIndex + 1))
                            .desc("")
                            .fileSize(fileSize)
                            .build());

                }
            } else {
                localVideos.add(LocalVideo.builder()
                        .isFailed(false)
                        .localFileFullPath(fullPath)
                        .title("P" + (videoIndex + 1))
                        .desc("")
                        .fileSize(fileSize)
                        .build());
                videoIndex++;
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
                log.error("upload video part fail, localVideoPart: {}", localVideo.getLocalFileFullPath());
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
            failedUploadVideo.setDeadline(biliVideoUploadResult.getDeadline());
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
        String biliCookies = configManager.getConfig().getPersonInfo().getBiliCookies();
        BiliPreUploadModel biliPreUploadModel = new BiliPreUploadModel(videoName, fileSize, biliCookies);
        if (StringUtils.isBlank(biliPreUploadModel.getUploadId())) {
            log.error("video preUpload info fetch error, videoName: {}", videoFile);
            throw new StreamerHelperException(ErrorEnum.UPLOAD_CHUNK_ERROR);
        }
        BiliPreUploadInfoModel biliPreUploadInfo = biliPreUploadModel.getBiliPreUploadVideoInfo();
        Map<String, String> extension = buildExtension(uploadModel, biliPreUploadModel);
        uploadResult.setPreUploadModel(biliPreUploadModel);
        uploadResult.setDeadline(System.currentTimeMillis() + biliPreUploadInfo.getTimeout() * 1000);

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
            log.info("video chunks upload success, videoName: {}", videoName);
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

    private Map<String, String> buildExtension(BiliVideoUploadTask uploadModel, BiliPreUploadModel biliPreUploadModel) {
        Map<String, String> extension = Maps.newHashMap();
        extension.put(BILI_UPLOAD_URL, biliPreUploadModel.getUploadUrl());
        extension.put(BILI_UPLOAD_ID, biliPreUploadModel.getUploadId());
        extension.put(BILI_UPOS_URI, biliPreUploadModel.getBiliPreUploadVideoInfo().getUposUri());
        extension.put(BILI_UPOS_AUTH, biliPreUploadModel.getBiliPreUploadVideoInfo().getAuth());
        extension.put(BILI_VIDEO_TILE, uploadModel.getTitle());
        extension.put(BILI_VIDEO_DYNAMIC, uploadModel.getDynamic());
        extension.put(BILI_VIDEO_DESC, uploadModel.getDesc());
        return extension;
    }


    public boolean checkNeedUpload(BiliVideoUploadTask uploadModel) {
        // 不上传本地直接返回
        if (BooleanUtils.isNotTrue(uploadModel.getUploadLocalFile())) {
            log.info("user config => {} uploadLocalFile {}. Upload Give up...", uploadModel.getRecorderName(),
                    uploadModel.getUploadLocalFile());
            return false;
        }

        // 没有对应文件夹直接返回
        if (StringUtils.isBlank(uploadModel.getDirName())) {
            log.error("filePath not existed for: {}.", uploadModel.getRecorderName());
            return false;
        }

        // 当前视频任务是否在上传中
        if (statusManager.isRecordOnSubmission(uploadModel.getDirName())) {
            log.error("videos in {} is on submission, do upload repeat", uploadModel.getDirName());
            return false;
        }
        return true;
    }


    private void syncUserInfo(BiliUploadUser uploadUser) {
        configManager.syncUploadPersonInfoToConfig(
                UploadPersonInfo.builder()
                        .accessToken(uploadUser.getAccessToken())
                        .mid(uploadUser.getMid())
                        .refreshToken(uploadUser.getRefreshToken())
                        .expiresIn(uploadUser.getExpiresIn())
                        .nickname(uploadUser.getNickName())
                        .tokenSignDate(uploadUser.getTokenSignTime())
                        .build()
        );
    }

    public BiliVideoUploadTask convertToUploadModel(RecordTask recordTask) {
        StreamerInfo streamerInfo = recordTask.getStreamerInfo();
        return BiliVideoUploadTask.builder()
                .appSecret(UploadConstant.BILI_VIDEO_UPLOAD_APP_SECRET)
                .streamerName(recordTask.getStreamerInfo().getName())
                .videoPartLimitSizeInput(
                        Optional.ofNullable(configManager.getConfig().getStreamerHelper().getVideoPartLimitSize())
                                .orElse(100))
                .dirName(recordTask.getDirName())
                .mid(Optional.ofNullable(configManager.getConfig().getPersonInfo().getMid()).orElse(0L))
                .accessToken(Optional.ofNullable(
                        configManager.getConfig().getPersonInfo().getAccessToken()).orElse("xxx"))
                .copyright(Optional.ofNullable(streamerInfo.getCopyright()).orElse(2))
                // todo 支持封面
                .cover("")
                .desc(Optional.ofNullable(streamerInfo.getDesc()).orElse("视频投稿"))
                .noRePrint(0)
                .openElec(1)
                .source(Optional.ofNullable(streamerInfo.getSource()).orElse(genDefaultDesc(recordTask)))
                .tags(streamerInfo.getTags())
                .tid(streamerInfo.getTid())
                .title(genVideoTitle(recordTask))
                .dynamic(Optional.ofNullable(recordTask.getStreamerInfo().getDynamic())
                        .orElse(genDefaultDesc(recordTask)))
                .uploadLocalFile(Optional.ofNullable(streamerInfo.getUploadLocalFile()).orElse(true))
                .recorderName(recordTask.getRecorderName())
                .deadline(0L)
                .uploadStart(0L)
                .succeedUploaded(Lists.newArrayList())
                .succeedUploadChunk(0)
                .succeedTotalLength(0)
                .build();

    }

    private String genVideoTitle(RecordTask recordTask) {
        Map<String, String> paramsMap = Maps.newHashMap();
        paramsMap.put("time", recordTask.getTimeV());
        paramsMap.put("name", recordTask.getRecorderName());
        if (StringUtils.isNotBlank(recordTask.getStreamerInfo().getTemplateTitle())) {
            StringSubstitutor sub = new StringSubstitutor(paramsMap);
            return sub.replace(recordTask.getStreamerInfo().getTemplateTitle());
        } else {
            return recordTask.getRecorderName() + " " + recordTask.getTimeV() + " " + "录播";
        }
    }

    private String genDefaultDesc(RecordTask recordTask) {
        return recordTask.getRecorderName() +
                " 直播间：" + recordTask.getStreamerInfo().getRoomUrl();
    }
}
