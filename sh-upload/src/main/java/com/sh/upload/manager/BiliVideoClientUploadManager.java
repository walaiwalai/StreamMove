package com.sh.upload.manager;

import cn.hutool.core.io.FileUtil;
import cn.hutool.http.HttpUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.sh.config.exception.ErrorEnum;
import com.sh.config.exception.StreamerHelperException;
import com.sh.config.manager.ConfigManager;
import com.sh.config.model.config.StreamerInfo;
import com.sh.config.model.stauts.FileStatusModel;
import com.sh.config.model.video.*;
import com.sh.config.utils.HttpClientUtil;
import com.sh.config.utils.VideoFileUtils;
import com.sh.engine.manager.StatusManager;
import com.sh.engine.model.record.RecordTask;
import com.sh.upload.constant.UploadConstant;
import com.sh.upload.model.BiliPreUploadInfoModel;
import com.sh.upload.model.BiliVideoUploadTask;
import com.sh.upload.model.web.BiliPreUploadRespose;
import com.sh.upload.model.web.BiliVideoUploadResultModel;
import com.sh.upload.service.BiliWorkUploadServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.FileFilterUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.StringSubstitutor;
import org.apache.http.HttpEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.mime.FormBodyPartBuilder;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.entity.mime.content.StringBody;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.File;
import java.io.FileInputStream;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import static com.sh.upload.constant.UploadConstant.SERVER_FILE_NAME;

/**
 * b站客户端上传视频文件
 *
 * @author caiWen
 * @date 2023/1/25 18:58
 */
@Component
@Slf4j
public class BiliVideoClientUploadManager {
    @Resource
    ConfigManager configManager;
    @Resource
    StatusManager statusManager;
    @Resource
    BiliWorkUploadServiceImpl biliVideoUploadService;

    public static final Map<String, String> BILI_HEADERS = Maps.newHashMap();
    private static final String BILI_PRE_URL
            = "https://member.bilibili.com/preupload?access_key=${accessToken}&mid=${mid}&profile=ugcfr%2Fpc3";
    /**
     * 视频上传分块大小为2M
     */
    private static final int CHUNK_SIZE = 1024 * 1024 * 2;

    /**
     * 上传视频线程池
     */
    private static final int UPLOAD_CORE_SIZE = 4;
    private static final ExecutorService UPLOAD_POOL = new ThreadPoolExecutor(
            4,
            4,
            600,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(120),
            new ThreadFactoryBuilder().setNameFormat("bili-upload-thread-%d").build(),
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    static {
        BILI_HEADERS.put("Connection", "alive");
        BILI_HEADERS.put("Content-Type", "multipart/form-data");
        BILI_HEADERS.put("User-Agent",
                "Mozilla/5.0 (Linux; Android 6.0; Nexus 5 Build/MRA58N) AppleWebKit/537.36 (KHTML, like Gecko) "
                        + "Chrome/109.0.0.0 Mobile Safari/537.36 Edg/109.0.1518.55");
        BILI_HEADERS.put("Accept-Encoding", "gzip,deflate");
    }


    public static boolean isUploadPoolAllWork() {
        return ((ThreadPoolExecutor) UPLOAD_POOL).getActiveCount() >= UPLOAD_CORE_SIZE;
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
            List<LocalVideo> localVideoParts = fetchLocalVideos(dirName, uploadModel);
            if (CollectionUtils.isEmpty(localVideoParts)) {
                log.info("no videos cab be upload, will return");
            }

            // 4.预上传视频
            log.info("Start to upload videoParts ...");
            List<RemoteSeverVideo> remoteVideoParts = uploadVideoParts(localVideoParts, uploadModel);
            log.info("Upload videoParts END, remoteVideos: {}", JSON.toJSONString(remoteVideoParts));
            if (CollectionUtils.isNotEmpty(uploadModel.getSucceedUploaded())) {
                log.info("Found succeed uploaded videos ... Concat ...");
                remoteVideoParts.addAll(uploadModel.getSucceedUploaded());
            }
            // 4.1 给需要上传的视频文件命名
            for (int i = 0; i < remoteVideoParts.size(); i++) {
                remoteVideoParts.get(i).setTitle("P" + (i + 1));
            }

            // 5.post视频
            log.info("Try to post Videos: {}", JSON.toJSONString(remoteVideoParts));
            boolean isPostSuccess = biliVideoUploadService.postWorkOnClient(uploadModel.getStreamerName(),
                    remoteVideoParts, ImmutableMap.of(UploadConstant.BILI_VIDEO_TILE, uploadModel.getTitle()));
            if (!isPostSuccess) {
                throw new StreamerHelperException(ErrorEnum.POST_WORK_ERROR);
            }

            log.info("upload video success.");

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
        List<File> sortedFile = VideoFileUtils.getFileSort(Lists.newArrayList(files));
        List<String> succeedPaths = Optional.ofNullable(uploadModel.getSucceedUploaded()).orElse(Lists.newArrayList())
                .stream().map(SucceedUploadSeverVideo::getLocalFileFullPath).collect(Collectors.toList());
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
            if (succeedPaths.contains(fullPath)) {
                log.info("video has been uploaded, path: {}", fullPath);
                continue;
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
                            .title("P" + (videoIndex))
                            .desc(streamerInfo.getDesc())
                            .fileSize(fileSize)
                            .build());

                }
            } else {
                localVideos.add(LocalVideo.builder()
                        .isFailed(false)
                        .localFileFullPath(fullPath)
                        .title("P" + (videoIndex))
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
     * @param localVideoParts
     * @param uploadModel
     * @return
     */
    private List<RemoteSeverVideo> uploadVideoParts(List<LocalVideo> localVideoParts, BiliVideoUploadTask uploadModel) {
        List<RemoteSeverVideo> remoteVideos = Lists.newArrayList();
        for (int i = 0; i < localVideoParts.size(); i++) {
            LocalVideo localVideo = localVideoParts.get(i);
            try {
                String uploadUrl, completeUploadUrl, serverFileName;
                if (localVideo.isFailed()) {
                    uploadUrl = Optional.ofNullable(uploadModel.getFailUpload().getUploadUrl()).orElse("");
                    completeUploadUrl = Optional.ofNullable(uploadModel.getFailUpload().getCompleteUploadUrl()).orElse(
                            "");
                    serverFileName = Optional.ofNullable(uploadModel.getFailUpload().getServerFileName()).orElse("");
                } else {
                    // 进行预上传
                    BiliPreUploadRespose preUploadData = getPreUploadData(uploadModel);
                    uploadUrl = preUploadData.getUrl();
                    completeUploadUrl = preUploadData.getComplete();
                    serverFileName = preUploadData.getFilename();
                }
                log.info("path: {}, serverFileName: {}, uploadUrl: {}, completeUploadUrl: {}",
                        uploadModel.getDirName(), serverFileName, uploadUrl, completeUploadUrl);

                // 进行上传
                BiliVideoUploadResultModel biliVideoUploadResult = uploadSingVideo(uploadUrl, completeUploadUrl,
                        serverFileName, localVideo);
                if (CollectionUtils.isNotEmpty(biliVideoUploadResult.getFailedChunks()) || !biliVideoUploadResult
                        .isComplete() || biliVideoUploadResult.getRemoteSeverVideo() == null) {
                    // 上传chunks失败，完成上传失败，发送作品为空均视为失败
                    localVideo.setFailed(true);
                    syncStatus(uploadModel.getDirName(), localVideo, biliVideoUploadResult);
                    throw new StreamerHelperException(ErrorEnum.UPLOAD_CHUNK_ERROR);
                }


                remoteVideos.add(biliVideoUploadResult.getRemoteSeverVideo());

                // 写文件状态
                syncStatus(uploadModel.getDirName(), localVideo, biliVideoUploadResult);

            } catch (Exception e) {
                log.error("upload video part fail, localVideoPart: {}", localVideo.getLocalFileFullPath(), e);
                statusManager.releaseRecordForSubmission(uploadModel.getDirName());
            }
        }
        return remoteVideos;
    }

    private BiliVideoUploadResultModel uploadSingVideo(String uploadUrl, String completeUrl, String serverFileName,
            LocalVideo localVideo) throws Exception {
        File videoFile = new File(localVideo.getLocalFileFullPath());
        String videoName = videoFile.getName();
        long fileSize = localVideo.getFileSize();
        BiliVideoUploadResultModel uploadResult = new BiliVideoUploadResultModel();

        // 1.获得预加载上传的b站视频地址信息
        Map<String, String> extension = ImmutableMap.of(SERVER_FILE_NAME, serverFileName);

        // 2.进行视频分块上传
        int partCount = (int) Math.ceil(fileSize * 1.0 / CHUNK_SIZE);
        log.info("video size is {}M, seg {} parts to upload.", fileSize / 1024 / 1024, partCount);
        CountDownLatch countDownLatch = new CountDownLatch(partCount);
        List<FailUploadVideoChunk> failUploadVideoChunks = Lists.newCopyOnWriteArrayList();
        for (int i = 0; i < partCount; i++) {
            //当前分段起始位置
            long curChunkStart = i * CHUNK_SIZE;
            // 当前分段大小  如果为最后一个大小为fileSize-curChunkStart  其他为partSize
            long curChunkSize = (i + 1 == partCount) ? (fileSize - curChunkStart) : CHUNK_SIZE;

            int finalI = i;
            CompletableFuture.supplyAsync(() -> {
                return biliVideoUploadService.uploadChunkOnClient(uploadUrl, videoFile, finalI, partCount,
                        (int) curChunkSize, curChunkStart, extension);
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

        // 3. 调用完成整个视频上传
        boolean isComplete = completeSingVideo(completeUrl, partCount, videoName, localVideo);
        uploadResult.setComplete(isComplete);
        if (!isComplete) {
            return uploadResult;
        }

        RemoteSeverVideo remoteSeverVideo = new RemoteSeverVideo(localVideo.getTitle(), localVideo.getDesc(),
                serverFileName);
        uploadResult.setRemoteSeverVideo(remoteSeverVideo);

        return uploadResult;
    }

    private boolean completeSingVideo(String completeUrl, int totalChunks, String videoName, LocalVideo localVideo)
            throws Exception {
        HttpEntity completeEntity = MultipartEntityBuilder.create()
                .addPart(FormBodyPartBuilder.create()
                        .setName("version")
                        .setBody(new StringBody("2.3.0.1088", ContentType.APPLICATION_FORM_URLENCODED))
                        .build())
                .addPart(FormBodyPartBuilder.create()
                        .setName("filesize")
                        .setBody(new StringBody(String.valueOf(localVideo.getFileSize()),
                                ContentType.APPLICATION_FORM_URLENCODED))
                        .build())
                .addPart(FormBodyPartBuilder.create()
                        .setName("chunks")
                        .setBody(new StringBody(String.valueOf(totalChunks), ContentType.APPLICATION_FORM_URLENCODED))
                        .build())
                .addPart(FormBodyPartBuilder.create()
                        .setName("md5")
                        .setBody(new StringBody(
                                DigestUtils.md5Hex(new FileInputStream(localVideo.getLocalFileFullPath())),
                                ContentType.APPLICATION_FORM_URLENCODED))
                        .build())
                .addPart(FormBodyPartBuilder.create()
                        .setName("name")
                        .setBody(new StringBody(videoName, ContentType.APPLICATION_FORM_URLENCODED))
                        .build())
                .build();
        String completeResp = HttpClientUtil.sendPost(completeUrl, null, completeEntity);
        JSONObject respObj = JSONObject.parseObject(completeResp);
        if (Objects.equals(respObj.getString("OK"), "1")) {
            log.info("complete upload success, videoName: {}", videoName);
            return true;
        } else {
            log.error("complete upload fail, videoName: {}", videoName);
            return false;
        }
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

    /**
     * 预上传接口请求
     *
     * @param uploadModel
     * @return
     */
    public BiliPreUploadRespose getPreUploadData(BiliVideoUploadTask uploadModel) {
        Map<String, String> urlParams = ImmutableMap.of(
                "accessToken", configManager.getUploadPersonInfo().getAccessToken(),
                "mid", configManager.getUploadPersonInfo().getMid().toString()
        );
        StringSubstitutor sub = new StringSubstitutor(urlParams);
        String preUrl = sub.replace(BILI_PRE_URL);

        String resp = HttpUtil.get(preUrl);
        BiliPreUploadRespose biliPreUploadRespose = JSON.parseObject(resp, BiliPreUploadRespose.class);

        if (biliPreUploadRespose.getOK() != 1) {
            log.error("video preUpload fail, resp: {}", resp);
            return null;
        }

        String uploadUrl = biliPreUploadRespose.getUrl();
        String[] params = StringUtils.split(uploadUrl, "?")[1].split("\\u0026");
        Map<String, String> paramMap = Maps.newHashMap();
        for (String param : params) {
            String[] split = param.split("=");
            paramMap.put(split[0], split[1]);
        }

        // 拼接一些参数到uploadModel
        String deadline = paramMap.get("deadline");
        if (StringUtils.isNotBlank(deadline)) {
            uploadModel.setDeadline(Long.valueOf(deadline));
        }
        String uploadstart = paramMap.get("uploadstart");
        if (StringUtils.isNotBlank(uploadstart)) {
            uploadModel.setUploadStart(Long.valueOf(uploadstart));
        }

        return biliPreUploadRespose;
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
