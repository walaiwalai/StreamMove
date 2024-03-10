//package com.sh.engine.service;
//
//import cn.hutool.core.io.FileUtil;
//import cn.hutool.core.io.file.FileNameUtil;
//import cn.hutool.core.util.URLUtil;
//import com.alibaba.fastjson.JSON;
//import com.google.common.collect.ImmutableMap;
//import com.google.common.collect.Lists;
//import com.google.common.collect.Maps;
//import com.google.common.util.concurrent.ThreadFactoryBuilder;
//import com.sh.config.exception.ErrorEnum;
//import com.sh.config.exception.StreamerRecordException;
//import com.sh.config.manager.ConfigFetcher;
//import com.sh.config.model.stauts.FileStatusModel;
//import com.sh.config.model.video.*;
//import com.sh.config.utils.VideoFileUtils;
//import com.sh.engine.constant.RecordConstant;
//import com.sh.engine.model.bili.BiliVideoUploadTask;
//import com.sh.engine.model.bili.BiliWebPreUploadCommand;
//import com.sh.engine.model.bili.BiliWebPreUploadParams;
//import com.sh.engine.model.bili.web.BiliClientPreUploadParams;
//import com.sh.engine.model.bili.web.VideoUploadResultModel;
//import com.sh.engine.model.upload.BaseUploadTask;
//import com.sh.engine.upload.PlatformWorkUploadService;
//import lombok.extern.slf4j.Slf4j;
//import org.apache.commons.collections4.CollectionUtils;
//import org.apache.commons.io.FileUtils;
//import org.apache.commons.io.filefilter.FileFilterUtils;
//import org.apache.commons.lang3.StringUtils;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.stereotype.Component;
//
//import javax.annotation.Resource;
//import java.io.File;
//import java.util.*;
//import java.util.concurrent.*;
//import java.util.stream.Collectors;
//
//import static com.sh.engine.constant.RecordConstant.*;
//
///**
// * @Author caiwen
// * @Date 2023 12 21 00 03
// **/
//@Component
//@Slf4j
//public class VideoUploadServiceImpl implements VideoUploadService {
//    @Resource(name = "biliClient")
//    PlatformWorkUploadService biliVideoClientUploadService;
//    @Resource(name = "biliWeb")
//    PlatformWorkUploadService biliWebUploadService;
//
//
//    /**
//     * 视频上传分块大小为2M
//     */
//    private static final int CHUNK_SIZE = 1024 * 1024 * 5;
//
//    @Autowired
//    private MsgSendService msgSendService;
//
//    /**
//     * 上传视频线程池
//     */
//    private static final ExecutorService UPLOAD_POOL = new ThreadPoolExecutor(
//            8,
//            8,
//            600,
//            TimeUnit.SECONDS,
//            new ArrayBlockingQueue<>(20480),
//            new ThreadFactoryBuilder().setNameFormat("bili-upload-thread-%d").build(),
//            new ThreadPoolExecutor.CallerRunsPolicy()
//    );
//
//    @Override
//    public void upload(BiliVideoUploadTask uploadModel) throws Exception {
//        String dirName = uploadModel.getDirName();
//
//        // 3. 获取本地视频文件
//        List<LocalVideo> localVideoParts = fetchLocalVideos(uploadModel);
//
//        // 4.预上传视频
//        List<RemoteSeverVideo> remoteVideos = Lists.newArrayList();
//        if (CollectionUtils.isNotEmpty(uploadModel.getSucceedUploaded())) {
//            // 4.1 上次上传成功的
//            log.info("Found succeed uploaded videos ... Concat ...");
//            remoteVideos.addAll(uploadModel.getSucceedUploaded());
//        }
//
//        // 4.2 需要上传的
//        List<RemoteSeverVideo> remoteVideoParts = doUpload(localVideoParts, uploadModel);
//        log.info("upload videoParts end, remoteVideos: {}", JSON.toJSONString(remoteVideoParts));
//        remoteVideos.addAll(remoteVideoParts);
//
//        // 4.2 给需要上传的视频文件命名
//        for (int i = 0; i < remoteVideos.size(); i++) {
//            remoteVideos.get(i).setTitle("P" + (i + 1));
//        }
//
//        // 5.post视频
//        log.info("Try to post Videos: {}", JSON.toJSONString(remoteVideos));
//        boolean isPostSuccess = doPost(uploadModel.getStreamerName(), remoteVideos, uploadModel);
//        if (!isPostSuccess) {
//            throw new StreamerRecordException(ErrorEnum.POST_WORK_ERROR);
//        }
//
//        log.info("upload video success.");
//
//        // 6.更新文件属性
//        FileStatusModel.updateToFile(dirName, FileStatusModel.builder().isPost(true).build());
//    }
//
//
//    private List<RemoteSeverVideo> doUpload(List<LocalVideo> localVideoParts, BiliVideoUploadTask uploadModel) throws Exception {
//        Integer uploadType = ConfigFetcher.getInitConfig().getUploadType();
//        List<RemoteSeverVideo> res;
//        if (uploadType == 1) {
//            res = uploadVideoOnWeb(localVideoParts, uploadModel);
//        } else {
//            res = uploadVideoOnClient(localVideoParts, uploadModel);
//        }
//        return res;
//    }
//
//    private boolean doPost(String streamerName, List<RemoteSeverVideo> remoteSeverVideos, BiliVideoUploadTask uploadModel) {
//        Integer uploadType = ConfigFetcher.getInitConfig().getUploadType();
//        if (uploadType == 1) {
//            Map<String, String> extension = Maps.newHashMap();
//            extension.put(BILI_VIDEO_TILE, uploadModel.getTitle());
//            extension.put(BILI_UPOS_AUTH, uploadModel.getBiliPreUploadInfo().getAuth());
//            return biliWebUploadService.postWork(streamerName, remoteSeverVideos, extension);
//        } else {
//            ImmutableMap<String, String> extension = ImmutableMap.of(BILI_VIDEO_TILE, uploadModel.getTitle());
//            return biliVideoClientUploadService.postWork(streamerName, remoteSeverVideos, extension);
//        }
//    }
//
//    private List<LocalVideo> fetchLocalVideos(BaseUploadTask task) {
//        String dirName = task.getDirName();
//        long videoPartLimitSize = ConfigFetcher.getInitConfig().getVideoPartLimitSize() * 1024L * 1024L;
//
//        // 遍历本地的视频文件
//        Collection<File> files = FileUtils.listFiles(new File(dirName), FileFilterUtils.suffixFileFilter("mp4"), null);
//        List<File> sortedFiles = VideoFileUtils.getFileSort(Lists.newArrayList(files));
//        List<String> succeedPaths = task.getSucceedUploaded().stream().map(SucceedUploadSeverVideo::getLocalFileFullPath).collect(Collectors.toList());
//
//        List<LocalVideo> localVideos = Lists.newArrayList();
//        for (File subVideoFile : sortedFiles) {
//            String fullPath = subVideoFile.getAbsolutePath();
//            String fileName = FileNameUtil.getPrefix(subVideoFile);
//            // 过小的视频文件不上场
//            long fileSize = FileUtil.size(subVideoFile);
//            if (fileSize < videoPartLimitSize) {
//                log.info("video size too small, give up upload, fileName: {}, size: {}, limitSize: {}",
//                        subVideoFile.getName(), fileSize, videoPartLimitSize);
//                continue;
//            }
//
//            // 已经上传的文件不重复上传
//            if (succeedPaths.contains(fullPath)) {
//                log.info("video has been uploaded, path: {}", fullPath);
//                continue;
//            }
//
////            if (task.getIsUploadFail()) {
////                // 处理上传失败的视频
////                boolean isCurFailedPart = Objects.equals(Optional.ofNullable(failedUploadVideo)
////                        .map(FailedUploadVideo::getLocalFileFullPath)
////                        .orElse(null), fullPath);
////                if (isCurFailedPart) {
////                    log.info("push upload error video to videoParts");
////                    localVideos.add(LocalVideo.builder()
////                            .localFileFullPath(Optional.ofNullable(task.getFailUpload())
////                                    .map(FailedUploadVideo::getLocalFileFullPath)
////                                    .orElse(null))
////                            .title(fileName)
////                            .fileSize(fileSize)
////                            .build());
////
////                }
////            } else {
////                localVideos.add(LocalVideo.builder()
////                        .localFileFullPath(fullPath)
////                        .title(fileName)
////                        .fileSize(fileSize)
////                        .build());
////            }
//        }
//        log.info("Final videoParts: {}", JSON.toJSONString(localVideos));
//        return localVideos;
//    }
//
//    /**
//     * 上传分段视频
//     *
//     * @param localVideoParts
//     * @param uploadModel
//     * @return
//     */
//    private List<RemoteSeverVideo> uploadVideoOnClient(List<LocalVideo> localVideoParts, BiliVideoUploadTask uploadModel) throws Exception {
//        String dirName = uploadModel.getDirName();
//
//        List<RemoteSeverVideo> remoteVideos = Lists.newArrayList();
//        for (int i = 0; i < localVideoParts.size(); i++) {
//            LocalVideo localVideo = localVideoParts.get(i);
//            // 进行上传
//            VideoUploadResultModel biliVideoUploadResult = uploadOnClient(localVideo);
//            if (CollectionUtils.isNotEmpty(biliVideoUploadResult.getFailedChunks()) || !biliVideoUploadResult
//                    .isComplete() || biliVideoUploadResult.getRemoteSeverVideo() == null) {
//                // 上传chunks失败，完成上传失败，发送作品为空均视为失败
////                localVideo.setFailed(true);
//                syncStatus(dirName, localVideo, biliVideoUploadResult);
//                throw new StreamerRecordException(ErrorEnum.UPLOAD_CHUNK_ERROR);
//            } else {
//                syncStatus(dirName, localVideo, biliVideoUploadResult);
//            }
//
//            // 写文件状态
//            remoteVideos.add(biliVideoUploadResult.getRemoteSeverVideo());
//
//            msgSendService.send(localVideo.getLocalFileFullPath() + "路径下的视频上传成功！");
//        }
//        return remoteVideos;
//    }
//
//    /**
//     * 上传分段视频
//     *
//     * @param localVideoParts
//     * @param uploadModel
//     * @return
//     */
//    private List<RemoteSeverVideo> uploadVideoOnWeb(List<LocalVideo> localVideoParts, BiliVideoUploadTask uploadModel) throws Exception {
//        String dirName = uploadModel.getDirName();
//
//        List<RemoteSeverVideo> remoteVideos = Lists.newArrayList();
//        for (int i = 0; i < localVideoParts.size(); i++) {
//            LocalVideo localVideo = localVideoParts.get(i);
//            // 进行上传
//            VideoUploadResultModel biliVideoUploadResult = uploadOnWeb(localVideo, uploadModel);
//            if (CollectionUtils.isNotEmpty(biliVideoUploadResult.getFailedChunks()) || !biliVideoUploadResult
//                    .isComplete() || biliVideoUploadResult.getRemoteSeverVideo() == null) {
//                // 上传chunks失败，完成上传失败，发送作品为空均视为失败
//                localVideo.setUpload(false);
//                syncStatus(dirName, localVideo, biliVideoUploadResult);
//                throw new StreamerRecordException(ErrorEnum.UPLOAD_CHUNK_ERROR);
//            } else {
//                localVideo.setUpload(true);
//                syncStatus(dirName, localVideo, biliVideoUploadResult);
//            }
//
//            // 写文件状态
//            remoteVideos.add(biliVideoUploadResult.getRemoteSeverVideo());
//
//            msgSendService.send(localVideo.getLocalFileFullPath() + "路径下的视频上传成功！");
//        }
//        return remoteVideos;
//    }
//
//    public VideoUploadResultModel uploadOnWeb(LocalVideo localVideo, BiliVideoUploadTask uploadModel) throws Exception {
//        File videoFile = new File(localVideo.getLocalFileFullPath());
//        String videoName = videoFile.getName();
//        long fileSize = localVideo.getFileSize();
//        VideoUploadResultModel uploadResult = new VideoUploadResultModel();
//
//        // 1.获得预加载上传的b站视频地址信息
//        BiliWebPreUploadCommand command = new BiliWebPreUploadCommand(videoFile);
//        command.doWebPreUp();
//
//        BiliWebPreUploadParams biliPreUploadInfo = command.getBiliWebPreUploadParams();
//        uploadModel.setBiliPreUploadInfo(biliPreUploadInfo);
//
//        Map<String, String> extension = buildExtension(localVideo, command);
//
//        // 2.进行视频分块上传
//        Integer chunkSize = biliPreUploadInfo.getChunkSize();
//        int partCount = (int) Math.ceil(fileSize * 1.0 / chunkSize);
//        log.info("video size is {}M, seg {} parts to upload.", fileSize / 1024 / 1024, partCount);
//        CountDownLatch countDownLatch = new CountDownLatch(partCount);
//        List<FailUploadVideoChunk> failUploadVideoChunks = Lists.newCopyOnWriteArrayList();
//        for (int i = 0; i < partCount; i++) {
//            long curChunkStart = (long) i * chunkSize;
//            long curChunkSize = (i + 1 == partCount) ? (fileSize - curChunkStart) : chunkSize;
//            long curChunkEnd = curChunkStart + curChunkSize;
//
//            int finalI = i;
//            CompletableFuture.supplyAsync(() -> {
//                        String chunkUploadUrl = RecordConstant.BILI_VIDEO_CHUNK_UPLOAD_URL
//                                .replace("{uploadUrl}", extension.get("uploadUrl"))
//                                .replace("{partNumber}", String.valueOf(finalI + 1))
//                                .replace("{uploadId}", extension.get("uploadId"))
//                                .replace("{chunk}", String.valueOf(finalI))
//                                .replace("{chunks}", String.valueOf(partCount))
//                                .replace("{size}", String.valueOf(curChunkSize))
//                                .replace("{start}", String.valueOf(curChunkStart))
//                                .replace("{end}", String.valueOf(curChunkEnd))
//                                .replace("{total}", String.valueOf(fileSize));
//                        return biliWebUploadService.uploadChunk(chunkUploadUrl, videoFile, finalI, partCount,
//                                (int) curChunkSize, curChunkStart, extension);
//                    }, UPLOAD_POOL)
//                    .whenComplete((isSuccess, throwbale) -> {
//                        if (!isSuccess) {
//                            FailUploadVideoChunk failUploadVideoChunk = new FailUploadVideoChunk();
//                            failUploadVideoChunk.setChunkStart(curChunkStart);
//                            failUploadVideoChunk.setCurChunkSize(curChunkSize);
//                            failUploadVideoChunk.setChunkNo(finalI);
//                            failUploadVideoChunks.add(failUploadVideoChunk);
//                        }
//                        countDownLatch.countDown();
//                    });
//        }
//
//        countDownLatch.await(3, TimeUnit.HOURS);
//
//
//        if (CollectionUtils.isEmpty(failUploadVideoChunks)) {
//            log.info("video chunks upload success, videoPath: {}", localVideo.getLocalFileFullPath());
//        } else {
//            log.error("video chunks upload fail, failed chunkNos: {}", failUploadVideoChunks.stream().map(
//                    FailUploadVideoChunk::getChunkNo).collect(Collectors.toList()));
//            uploadResult.setFailedChunks(failUploadVideoChunks);
//            return uploadResult;
//        }
//
//        // 3. 完成分块的上传
//        String finishUrl = String.format(RecordConstant.BILI_CHUNK_UPLOAD_FINISH_URL,
//                biliPreUploadInfo.getUploadUrl(),
//                URLUtil.encode(videoName),
//                biliPreUploadInfo.getUploadId(),
//                biliPreUploadInfo.getBizId()
//        );
//        boolean isFinish = biliWebUploadService.finishChunks(finishUrl, partCount, videoName, localVideo, extension);
//        uploadResult.setComplete(isFinish);
//        if (isFinish) {
//            log.info("video finish upload success, videoName: {}", videoName);
//        } else {
//            log.error("video finish upload fail, videoName: {}", videoName);
//            return uploadResult;
//        }
//
//        // 4. 组装服务器端的视频
//        String uposUri = biliPreUploadInfo.getUposUri();
//        String[] tmps = uposUri.split("//")[1].split("/");
//        String fileNameOnServer = tmps[tmps.length - 1].split(".mp4")[0];
//        RemoteSeverVideo remoteSeverVideo = new RemoteSeverVideo(localVideo.getTitle(), fileNameOnServer);
//        uploadResult.setRemoteSeverVideo(remoteSeverVideo);
//        return uploadResult;
//    }
//
//    private Map<String, String> buildExtension(LocalVideo localVideo, BiliWebPreUploadCommand biliWebPreUploadCommand) {
//        Map<String, String> extension = Maps.newHashMap();
//        extension.put(BILI_UPLOAD_URL, biliWebPreUploadCommand.getBiliWebPreUploadParams().getUploadUrl());
//        extension.put(BILI_UPLOAD_ID, biliWebPreUploadCommand.getBiliWebPreUploadParams().getUploadId());
//        extension.put(BILI_UPOS_URI, biliWebPreUploadCommand.getBiliWebPreUploadParams().getUposUri());
//        extension.put(BILI_UPOS_AUTH, biliWebPreUploadCommand.getBiliWebPreUploadParams().getAuth());
//        extension.put(BILI_VIDEO_TILE, localVideo.getTitle());
//        return extension;
//    }
//
//    private VideoUploadResultModel uploadOnClient(LocalVideo localVideo) throws Exception {
//        File videoFile = new File(localVideo.getLocalFileFullPath());
//        BiliWebPreUploadCommand command = new BiliWebPreUploadCommand(videoFile);
//        command.doClientPreUp();
//
//        BiliClientPreUploadParams preUploadData = command.getBiliClientPreUploadParams();
//        String uploadUrl = preUploadData.getUrl();
//        String completeUrl = preUploadData.getComplete();
//        String serverFileName = preUploadData.getFilename();
//
//        String videoName = videoFile.getName();
//        long fileSize = localVideo.getFileSize();
//        VideoUploadResultModel uploadResult = new VideoUploadResultModel();
//
//        // 1.获得预加载上传的b站视频地址信息
//        Map<String, String> extension = ImmutableMap.of(SERVER_FILE_NAME, serverFileName);
//
//        // 2.进行视频分块上传
//        int partCount = (int) Math.ceil(fileSize * 1.0 / CHUNK_SIZE);
//        log.info("video size is {}M, seg {} parts to upload.", fileSize / 1024 / 1024, partCount);
//        CountDownLatch countDownLatch = new CountDownLatch(partCount);
//        List<FailUploadVideoChunk> failUploadVideoChunks = Lists.newCopyOnWriteArrayList();
//        for (int i = 0; i < partCount; i++) {
//            //当前分段起始位置
//            long curChunkStart = (long) i * CHUNK_SIZE;
//            // 当前分段大小  如果为最后一个大小为fileSize-curChunkStart  其他为partSize
//            long curChunkSize = (i + 1 == partCount) ? (fileSize - curChunkStart) : CHUNK_SIZE;
//
//            int finalI = i;
//            CompletableFuture.supplyAsync(() -> {
//                        return biliVideoClientUploadService.uploadChunk(uploadUrl, videoFile, finalI, partCount,
//                                (int) curChunkSize, curChunkStart, extension);
//                    }, UPLOAD_POOL)
//                    .whenComplete((isSuccess, throwbale) -> {
//                        if (!isSuccess) {
//                            FailUploadVideoChunk failUploadVideoChunk = new FailUploadVideoChunk();
//                            failUploadVideoChunk.setChunkStart(curChunkStart);
//                            failUploadVideoChunk.setCurChunkSize(curChunkSize);
//                            failUploadVideoChunk.setChunkNo(finalI);
//                            failUploadVideoChunks.add(failUploadVideoChunk);
//                        }
//                        countDownLatch.countDown();
//                    });
//        }
//
//        countDownLatch.await();
//
//        if (CollectionUtils.isEmpty(failUploadVideoChunks)) {
//            log.info("video chunks upload success, videoPath: {}", localVideo.getLocalFileFullPath());
//        } else {
//            log.error("video chunks upload fail, failed chunkNos: {}", failUploadVideoChunks.stream().map(
//                    FailUploadVideoChunk::getChunkNo).collect(Collectors.toList()));
//            uploadResult.setFailedChunks(failUploadVideoChunks);
//            return uploadResult;
//        }
//
//        // 3. 调用完成整个视频上传
//        boolean isComplete = biliVideoClientUploadService.finishChunks(completeUrl, partCount, videoName, localVideo, null);
//        uploadResult.setComplete(isComplete);
//        if (!isComplete) {
//            return uploadResult;
//        }
//
//        RemoteSeverVideo remoteSeverVideo = new RemoteSeverVideo(localVideo.getTitle(), serverFileName);
//        uploadResult.setRemoteSeverVideo(remoteSeverVideo);
//
//        return uploadResult;
//    }
//
//
//    private void syncStatus(String dirName, LocalVideo localVideo, VideoUploadResultModel uploadResult) {
//        FileStatusModel fileStatusModel = FileStatusModel.loadFromFile(dirName);
//        UploadVideoPair videoPair = Optional.ofNullable(fileStatusModel.getVideoParts()).orElse(new UploadVideoPair());
//        List<SucceedUploadSeverVideo> exsitedSuccessUploadVideoParts = Optional.ofNullable(videoPair.getSucceedUploadedVideos())
//                .orElse(Lists.newArrayList());
//        FailedUploadVideo failedUploadVideo = Optional.ofNullable(videoPair.getFailedUploadVideo())
//                .orElse(new FailedUploadVideo());
//
//        boolean isAlreadyInStatus = exsitedSuccessUploadVideoParts.stream().anyMatch(
//                succeedUploadVideo -> StringUtils.equals(succeedUploadVideo.getLocalFileFullPath(),
//                        localVideo.getLocalFileFullPath()));
//
//        if (isAlreadyInStatus) {
//            log.info("Found Exist Video {}", localVideo.getLocalFileFullPath());
//            return;
//        }
//
//        if (localVideo.isUpload()) {
//            // 说明该localVideo上传成功了
//            SucceedUploadSeverVideo newSucceedPart = new SucceedUploadSeverVideo();
//            newSucceedPart.setLocalFileFullPath(localVideo.getLocalFileFullPath());
//            newSucceedPart.setFilename(uploadResult.getRemoteSeverVideo().getFilename());
//            newSucceedPart.setTitle(uploadResult.getRemoteSeverVideo().getTitle());
//            exsitedSuccessUploadVideoParts.add(newSucceedPart);
//            videoPair.setSucceedUploadedVideos(exsitedSuccessUploadVideoParts);
//        } else {
//            // 说明该localVideo上传失败了
//            failedUploadVideo.setLocalFileFullPath(localVideo.getLocalFileFullPath());
//            failedUploadVideo.setFailUploadVideoChunks(uploadResult.getFailedChunks());
//            videoPair.setFailedUploadVideo(failedUploadVideo);
//        }
//        fileStatusModel.setVideoParts(videoPair);
//
//        FileStatusModel.updateToFile(dirName, fileStatusModel);
//    }
//}
