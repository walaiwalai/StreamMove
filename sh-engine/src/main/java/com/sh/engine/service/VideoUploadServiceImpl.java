package com.sh.engine.service;

import cn.hutool.core.io.FileUtil;
import cn.hutool.http.HttpUtil;
import com.alibaba.fastjson.JSON;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.sh.config.exception.ErrorEnum;
import com.sh.config.exception.StreamerRecordException;
import com.sh.config.manager.ConfigFetcher;
import com.sh.config.model.stauts.FileStatusModel;
import com.sh.config.model.video.*;
import com.sh.config.utils.VideoFileUtils;
import com.sh.engine.constant.UploadConstant;
import com.sh.engine.model.bili.BiliVideoUploadTask;
import com.sh.engine.model.bili.web.BiliPreUploadRespose;
import com.sh.engine.model.bili.web.BiliVideoUploadResultModel;
import com.sh.engine.upload.PlatformWorkUploadService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.FileFilterUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.StringSubstitutor;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import static com.sh.engine.constant.UploadConstant.SERVER_FILE_NAME;

/**
 * @Author caiwen
 * @Date 2023 12 21 00 03
 **/
@Component
@Slf4j
public class VideoUploadServiceImpl implements VideoUploadService {
    @Resource(name = "biliClient")
    PlatformWorkUploadService biliVideoClientUploadService;


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
    private static final ExecutorService UPLOAD_POOL = new ThreadPoolExecutor(
            4,
            4,
            600,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(2048),
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

    @Override
    public void upload(BiliVideoUploadTask uploadModel) throws Exception {
        String dirName = uploadModel.getDirName();

        // 3. 获取本地视频文件
        List<LocalVideo> localVideoParts = fetchLocalVideos(uploadModel);
        if (CollectionUtils.isEmpty(localVideoParts)) {
            log.info("no videos cab be upload, will return, dirName: {}", dirName);
            return;
        }

        // 4.预上传视频
        log.info("start to upload videoParts...");
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
        boolean isPostSuccess = biliVideoClientUploadService.postWork(uploadModel.getStreamerName(),
                remoteVideoParts, ImmutableMap.of(UploadConstant.BILI_VIDEO_TILE, uploadModel.getTitle()));
        if (!isPostSuccess) {
            throw new StreamerRecordException(ErrorEnum.POST_WORK_ERROR);
        }

        log.info("upload video success.");

        // 6.更新文件属性
        FileStatusModel.updateToFile(dirName, FileStatusModel.builder().isPost(true).build());
    }

    private List<LocalVideo> fetchLocalVideos(BiliVideoUploadTask uploadModel) {
        String dirName = uploadModel.getDirName();
        long videoPartLimitSize = ConfigFetcher.getInitConfig().getVideoPartLimitSize() * 1024L * 1024L;
        Integer videoIndex = 0;

        // 遍历本地的视频文件
        Collection<File> files = FileUtils.listFiles(new File(dirName), FileFilterUtils.suffixFileFilter("mp4"), null);
        List<File> sortedFiles = VideoFileUtils.getFileSort(Lists.newArrayList(files));
        List<String> succeedPaths = uploadModel.getSucceedUploaded().stream().map(SucceedUploadSeverVideo::getLocalFileFullPath).collect(Collectors.toList());

        List<LocalVideo> localVideos = Lists.newArrayList();
        for (File subVideoFile : sortedFiles) {
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
//                            .desc(streamerInfo.getDesc())
                            .fileSize(fileSize)
                            .build());

                }
            } else {
                localVideos.add(LocalVideo.builder()
                        .isFailed(false)
                        .localFileFullPath(fullPath)
                        .title("P" + (videoIndex))
//                        .desc(streamerInfo.getDesc())
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
    private List<RemoteSeverVideo> uploadVideoParts(List<LocalVideo> localVideoParts, BiliVideoUploadTask uploadModel) throws Exception {
        String dirName = uploadModel.getDirName();

        List<RemoteSeverVideo> remoteVideos = Lists.newArrayList();
        for (int i = 0; i < localVideoParts.size(); i++) {
            LocalVideo localVideo = localVideoParts.get(i);
            String uploadUrl, completeUploadUrl, serverFileName;
            if (localVideo.isFailed()) {
                uploadUrl = Optional.ofNullable(uploadModel.getFailUpload().getUploadUrl()).orElse("");
                completeUploadUrl = Optional.ofNullable(uploadModel.getFailUpload().getCompleteUploadUrl()).orElse(
                        "");
                serverFileName = Optional.ofNullable(uploadModel.getFailUpload().getServerFileName()).orElse("");
            } else {
                // 进行预上传
                BiliPreUploadRespose preUploadData = getPreUploadData();
                uploadUrl = preUploadData.getUrl();
                completeUploadUrl = preUploadData.getComplete();
                serverFileName = preUploadData.getFilename();
            }
            log.info("path: {}, serverFileName: {}, uploadUrl: {}, completeUploadUrl: {}",
                    dirName, serverFileName, uploadUrl, completeUploadUrl);

            // 进行上传
            BiliVideoUploadResultModel biliVideoUploadResult = uploadSingVideo(uploadUrl, completeUploadUrl,
                    serverFileName, localVideo);
            if (CollectionUtils.isNotEmpty(biliVideoUploadResult.getFailedChunks()) || !biliVideoUploadResult
                    .isComplete() || biliVideoUploadResult.getRemoteSeverVideo() == null) {
                // 上传chunks失败，完成上传失败，发送作品为空均视为失败
                localVideo.setFailed(true);
                syncStatus(dirName, localVideo, biliVideoUploadResult);
                throw new StreamerRecordException(ErrorEnum.UPLOAD_CHUNK_ERROR);
            } else {
                syncStatus(dirName, localVideo, biliVideoUploadResult);
            }

            // 写文件状态
            remoteVideos.add(biliVideoUploadResult.getRemoteSeverVideo());
        }
        return remoteVideos;
    }

    /**
     * 预上传接口请求
     *
     * @return
     */
    public BiliPreUploadRespose getPreUploadData() throws Exception {
        Map<String, String> urlParams = ImmutableMap.of(
                "accessToken", ConfigFetcher.getInitConfig().getAccessToken(),
                "mid", ConfigFetcher.getInitConfig().getMid().toString()
        );
        StringSubstitutor sub = new StringSubstitutor(urlParams);
        String preUrl = sub.replace(BILI_PRE_URL);

        String resp = HttpUtil.get(preUrl);
        BiliPreUploadRespose biliPreUploadRespose = JSON.parseObject(resp, BiliPreUploadRespose.class);

        if (biliPreUploadRespose.getOK() != 1) {
            log.error("video preUpload fail, resp: {}", resp);
            throw new StreamerRecordException(ErrorEnum.PRE_UPLOAD_ERROR);
        }

        String uploadUrl = biliPreUploadRespose.getUrl();
        String[] params = StringUtils.split(uploadUrl, "?")[1].split("\\u0026");
        Map<String, String> paramMap = Maps.newHashMap();
        for (String param : params) {
            String[] split = param.split("=");
            paramMap.put(split[0], split[1]);
        }

//        // 拼接一些参数到uploadModel
//        String deadline = paramMap.get("deadline");
//        if (StringUtils.isNotBlank(deadline)) {
//            uploadModel.setDeadline(Long.valueOf(deadline));
//        }
//        String uploadstart = paramMap.get("uploadstart");
//        if (StringUtils.isNotBlank(uploadstart)) {
//            uploadModel.setUploadStart(Long.valueOf(uploadstart));
//        }

        return biliPreUploadRespose;
    }

    private BiliVideoUploadResultModel uploadSingVideo(String uploadUrl, String completeUrl,
                                                       String serverFileName, LocalVideo localVideo) throws Exception {
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
                        return biliVideoClientUploadService.uploadChunk(uploadUrl, videoFile, finalI, partCount,
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

        countDownLatch.await(3, TimeUnit.HOURS);

        if (CollectionUtils.isEmpty(failUploadVideoChunks)) {
            log.info("video chunks upload success, videoPath: {}", localVideo.getLocalFileFullPath());
        } else {
            log.error("video chunks upload fail, failed chunkNos: {}", failUploadVideoChunks.stream().map(
                    FailUploadVideoChunk::getChunkNo).collect(Collectors.toList()));
            uploadResult.setFailedChunks(failUploadVideoChunks);
            return uploadResult;
        }

        // 3. 调用完成整个视频上传
        boolean isComplete = biliVideoClientUploadService.finishChunks(completeUrl, partCount, videoName, localVideo);
        uploadResult.setComplete(isComplete);
        if (!isComplete) {
            return uploadResult;
        }

        RemoteSeverVideo remoteSeverVideo = new RemoteSeverVideo(localVideo.getTitle(), localVideo.getDesc(),
                serverFileName);
        uploadResult.setRemoteSeverVideo(remoteSeverVideo);

        return uploadResult;
    }


    private void syncStatus(String dirName, LocalVideo localVideo, BiliVideoUploadResultModel uploadResult) {
        FileStatusModel fileStatusModel = FileStatusModel.loadFromFile(dirName);
        UploadVideoPair videoPair = Optional.ofNullable(fileStatusModel.getVideoParts()).orElse(new UploadVideoPair());
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

        if (localVideo.isFailed()) {
            // 说明该localVideo上传失败了
            failedUploadVideo.setLocalFileFullPath(localVideo.getLocalFileFullPath());
            failedUploadVideo.setFailUploadVideoChunks(uploadResult.getFailedChunks());
            videoPair.setFailedUploadVideo(failedUploadVideo);
        } else {
            // 说明该localVideo上传成功了
            SucceedUploadSeverVideo newSucceedPart = new SucceedUploadSeverVideo();
            newSucceedPart.setLocalFileFullPath(localVideo.getLocalFileFullPath());
            newSucceedPart.setFilename(uploadResult.getRemoteSeverVideo().getFilename());
            newSucceedPart.setDesc(uploadResult.getRemoteSeverVideo().getDesc());
            newSucceedPart.setTitle(uploadResult.getRemoteSeverVideo().getTitle());
            exsitedSuccessUploadVideoParts.add(newSucceedPart);
            videoPair.setSucceedUploadedVideos(exsitedSuccessUploadVideoParts);
        }
        fileStatusModel.setVideoParts(videoPair);

        FileStatusModel.updateToFile(dirName, fileStatusModel);
    }
}
