//package com.sh.engine.processor.uploader;
//
//import cn.hutool.core.io.FileUtil;
//import com.alibaba.fastjson.JSON;
//import com.alibaba.fastjson.JSONObject;
//import com.google.common.collect.Lists;
//import com.google.common.collect.Maps;
//import com.sh.config.exception.ErrorEnum;
//import com.sh.config.exception.StreamerRecordException;
//import com.sh.config.manager.ConfigFetcher;
//import com.sh.config.model.config.StreamerConfig;
//import com.sh.engine.model.video.RemoteSeverVideo;
//import com.sh.config.utils.ExecutorPoolUtil;
//import com.sh.config.utils.HttpClientUtil;
//import com.sh.config.utils.VideoFileUtil;
//import com.sh.engine.constant.RecordConstant;
//import com.sh.engine.constant.UploadPlatformEnum;
//import com.sh.engine.model.StreamerInfoHolder;
//import com.sh.engine.model.bili.BiliWebPreUploadCommand;
//import com.sh.engine.model.bili.BiliClientPreUploadParams;
//import com.sh.engine.processor.uploader.meta.BiliWorkMetaData;
//import com.sh.engine.service.VideoMergeService;
//import com.sh.message.service.MsgSendService;
//import lombok.extern.slf4j.Slf4j;
//import org.apache.commons.codec.digest.DigestUtils;
//import org.apache.commons.collections4.CollectionUtils;
//import org.apache.commons.io.FileUtils;
//import org.apache.commons.io.filefilter.FileFilterUtils;
//import org.apache.commons.lang3.StringUtils;
//import org.apache.http.HttpEntity;
//import org.apache.http.entity.ContentType;
//import org.apache.http.entity.mime.FormBodyPartBuilder;
//import org.apache.http.entity.mime.MultipartEntityBuilder;
//import org.apache.http.entity.mime.content.ByteArrayBody;
//import org.apache.http.entity.mime.content.StringBody;
//import org.springframework.stereotype.Component;
//
//import javax.annotation.Resource;
//import java.io.File;
//import java.io.IOException;
//import java.io.InputStream;
//import java.nio.file.Files;
//import java.nio.file.Paths;
//import java.util.*;
//import java.util.concurrent.CompletableFuture;
//import java.util.concurrent.CountDownLatch;
//import java.util.concurrent.atomic.AtomicBoolean;
//import java.util.function.Function;
//import java.util.stream.Collectors;
//
///**
// * @Author caiwen
// * @Date 2024 09 28 22 49
// **/
//@Slf4j
//@Component
//public class BiliClientUploader extends Uploader {
//    @Resource
//    private MsgSendService msgSendService;
//    @Resource
//    private VideoMergeService videoMergeService;
//
//    /**
//     * 视频上传分块大小为5M
//     */
//    private static final int CHUNK_SIZE = 1024 * 1024 * 5;
//    /**
//     * 失败重试次数
//     */
//    public static final Integer RETRY_COUNT = 10;
//    public static final Integer CHUNK_RETRY_DELAY = 500;
//
//    private static final String CLIENT_POST_VIDEO_URL
//            = "https://member.bilibili.com/x/vu/client/add?access_key=%s";
//    private static final String CLIENT_COVER_UPLOAD_URL
//            = "https://member.bilibili.com/x/vu/client/cover/up?access_key=%s";
//    private static final Map<String, String> CLIENT_HEADERS = Maps.newHashMap();
//
//    static {
//        CLIENT_HEADERS.put("Connection", "keep-alive");
//        CLIENT_HEADERS.put("Content-Type", "application/json");
//        CLIENT_HEADERS.put("user-agent", "");
//    }
//
//    @Override
//    public String getType() {
//        return UploadPlatformEnum.BILI_CLIENT.getType();
//    }
//
//    @Override
//    public void initUploader() {
//
//    }
//
//    @Override
//    public void preProcess(String recordPath) {
//        String streamerName = StreamerInfoHolder.getCurStreamerName();
//        StreamerConfig streamerConfig = ConfigFetcher.getStreamerInfoByName(streamerName);
//
//        // 合成视频片头
//        List<String> biliOpeningAnimations = streamerConfig.getBiliOpeningAnimations();
//        File highlightTmpDir = new File(recordPath, "tmp-h");
//        if (CollectionUtils.isNotEmpty(biliOpeningAnimations) && highlightTmpDir.exists()) {
//            mergeOpeningAnimations(recordPath, streamerConfig);
//        }
//    }
//
//    @Override
//    public boolean upload(String recordPath) throws Exception {
//        // 0. 获取要上传的文件
//        List<File> localVideos = fetchUploadVideos(recordPath);
//        if (CollectionUtils.isEmpty(localVideos)) {
//            return true;
//        }
//
//        // 1. 上传视频
//        List<RemoteSeverVideo> remoteVideos = Lists.newArrayList();
//        for (int i = 0; i < localVideos.size(); i++) {
//            File localVideo = localVideos.get(i);
//            RemoteSeverVideo uploadedVideo = getUploadedVideo(localVideo);
//            if (uploadedVideo != null) {
//                log.info("video has been uploaded, will skip, path: {}", localVideo.getAbsolutePath());
//                remoteVideos.add(uploadedVideo);
//                continue;
//            }
//
//            // 1.1 上传单个视频
//            uploadedVideo = uploadOnClient(localVideo);
//            if (uploadedVideo == null) {
//                // 上传失败，发送作品为空均视为失败
//                msgSendService.sendText(localVideo.getAbsolutePath() + "路径下的视频上传B站失败！");
//                throw new StreamerRecordException(ErrorEnum.UPLOAD_CHUNK_ERROR);
//            }
//
//            saveUploadedVideo(uploadedVideo);
//            remoteVideos.add(uploadedVideo);
//
//            // 1.2 发消息
//            msgSendService.sendText(localVideo.getAbsolutePath() + "路径下的视频上传B站成功！");
//        }
//
//        // 2. 提交作品
//        boolean isPostSuccess = postWork(remoteVideos, recordPath);
//        if (!isPostSuccess) {
//            throw new StreamerRecordException(ErrorEnum.POST_WORK_ERROR);
//        }
//
//        // 3. 提交成功清理一下缓存
//        clearUploadedVideos();
//
//        return true;
//    }
//
//    private void mergeOpeningAnimations(String recordPath, StreamerConfig streamerConfig) {
//        // 特殊逻辑： 如果带有b站片头视频，再合成一份新的视频
//        List<String> biliOpeningAnimations = streamerConfig.getBiliOpeningAnimations();
//        File highlightTmpDir = new File(recordPath, "tmp-h");
//
//        // 目标合成视频
//        File exclusiveDir = new File(recordPath, getType());
//        if (!exclusiveDir.exists()) {
//            exclusiveDir.mkdirs();
//        }
//
//        File targetFile = new File(exclusiveDir, RecordConstant.HL_VIDEO);
//        if (targetFile.exists()) {
//            return;
//        }
//
//        // 根据streamerName的hash随机取BiliOpeningAnimations的片头
//        int index = Math.abs(recordPath.hashCode() % biliOpeningAnimations.size());
//        String biliOpeningAnimation = biliOpeningAnimations.get(index);
//        List<String> localFps = FileUtils.listFiles(highlightTmpDir, FileFilterUtils.suffixFileFilter("ts"), null)
//                .stream()
//                .sorted(Comparator.comparingLong(File::lastModified))
//                .map(File::getAbsolutePath)
//                .collect(Collectors.toList());
//        int insertIndex = 0;
//        localFps.add(insertIndex, biliOpeningAnimation);
//
//
//        // 合并视频片头
//        boolean success = videoMergeService.concatDiffVideos(localFps, targetFile);
//        String msgPrefix = success ? "合并视频片头完成！路径为：" : "合并视频片头失败！路径为：";
//        msgSendService.sendText(msgPrefix + targetFile.getAbsolutePath());
//    }
//
//    /**
//     * 获取要上传的视频
//     *
//     * @param recordPath 文件地址
//     * @return 要上传的视频
//     */
//    private List<File> fetchUploadVideos(String recordPath) {
//        long videoPartLimitSize = ConfigFetcher.getInitConfig().getVideoPartLimitSize() * 1024L * 1024L;
//
//        // 遍历本地的视频文件
//        List<File> allVideos = FileUtils.listFiles(new File(recordPath), FileFilterUtils.suffixFileFilter("mp4"), null)
//                .stream()
//                .sorted(Comparator.comparingLong(File::lastModified))
//                .filter(file -> FileUtil.size(file) >= videoPartLimitSize)
//                .collect(Collectors.toList());
//
//
//        // 专属文件夹(优先替换)
//        File exclusiveDir = new File(recordPath, getType());
//        Collection<File> exclusiveFiles;
//        if (exclusiveDir.exists()) {
//            exclusiveFiles = FileUtils.listFiles(exclusiveDir, FileFilterUtils.suffixFileFilter("mp4"), null);
//        } else {
//            exclusiveFiles = Collections.emptyList();
//        }
//        Map<String, File> exclusiveFileMap = exclusiveFiles.stream().collect(Collectors.toMap(File::getName, Function.identity(), (v1, v2) -> v2));
//        List<File> res = Lists.newArrayList();
//        for (File video : allVideos) {
//            File addVideo = exclusiveFileMap.getOrDefault(video.getName(), video);
//            res.add(addVideo);
//            log.info("Final added video: {}", addVideo.getAbsolutePath());
//        }
//        return res;
//    }
//
//    private RemoteSeverVideo uploadOnClient(File videoFile) throws Exception {
//        BiliWebPreUploadCommand command = new BiliWebPreUploadCommand(videoFile);
//        command.doClientPreUp();
//
//        BiliClientPreUploadParams preUploadData = command.getBiliClientPreUploadParams();
//        String uploadUrl = preUploadData.getUrl();
//        String completeUrl = preUploadData.getComplete();
//        String serverFileName = preUploadData.getFilename();
//
//        String videoName = videoFile.getName();
//        long fileSize = FileUtil.size(videoFile);
//
//        // 1.进行视频分块上传
//        int partCount = (int) Math.ceil(fileSize * 1.0 / CHUNK_SIZE);
//        log.info("video size is {}M, seg {} parts to upload.", fileSize / 1024 / 1024, partCount);
//        CountDownLatch countDownLatch = new CountDownLatch(partCount);
//        List<Integer> failChunkNums = Lists.newCopyOnWriteArrayList();
//        AtomicBoolean hasFailed = new AtomicBoolean(false);
//
//        for (int i = 0; i < partCount; i++) {
//            //当前分段起始位置
//            long curChunkStart = (long) i * CHUNK_SIZE;
//            // 当前分段大小  如果为最后一个大小为fileSize-curChunkStart  其他为partSize
//            long curChunkSize = (i + 1 == partCount) ? (fileSize - curChunkStart) : CHUNK_SIZE;
//
//            int finalI = i;
//            CompletableFuture.supplyAsync(() -> {
//                        if (hasFailed.get()) {
//                            return false;
//                        }
//                        return uploadChunk(uploadUrl, videoFile, finalI, partCount, (int) curChunkSize, curChunkStart);
//                    }, ExecutorPoolUtil.getUploadPool())
//                    .whenComplete((isSuccess, throwbale) -> {
//                        if (!isSuccess && hasFailed.compareAndSet(false, true)) {
//                            failChunkNums.add(finalI);
//                        }
//                        countDownLatch.countDown();
//                    });
//        }
//        countDownLatch.await();
//
//        if (CollectionUtils.isEmpty(failChunkNums)) {
//            log.info("video chunks upload success, videoPath: {}", videoFile.getAbsolutePath());
//        } else {
//            log.error("video chunks upload fail, failed chunkNos: {}", JSON.toJSONString(failChunkNums));
//            return null;
//        }
//
//        // 2. 调用完成整个视频上传
//        boolean isComplete = finishChunks(completeUrl, partCount, videoName, videoFile);
//        if (!isComplete) {
//            return null;
//        }
//
//        return new RemoteSeverVideo(serverFileName, videoFile.getAbsolutePath());
//    }
//
//    private boolean uploadChunk(String uploadUrl, File targetFile, Integer chunkNo, Integer totalChunks, Integer curChunkSize, Long curChunkStart) {
//        int chunkShowNo = chunkNo + 1;
//        long startTime = System.currentTimeMillis();
//
//        byte[] bytes = null;
//        try {
//            bytes = VideoFileUtil.fetchBlock(targetFile, curChunkStart, curChunkSize);
//        } catch (IOException e) {
//            log.error("fetch chunk error", e);
//            return false;
//        }
//        String md5Str = DigestUtils.md5Hex(bytes);
//        HttpEntity requestEntity = MultipartEntityBuilder.create()
//                .addPart(FormBodyPartBuilder.create()
//                        .setName("version")
//                        .setBody(new StringBody("2.3.0.1088", ContentType.APPLICATION_FORM_URLENCODED))
//                        .build())
//                .addPart(FormBodyPartBuilder.create()
//                        .setName("filesize")
//                        .setBody(new StringBody(String.valueOf(curChunkSize), ContentType.APPLICATION_FORM_URLENCODED))
//                        .build())
//                .addPart(FormBodyPartBuilder.create()
//                        .setName("chunk")
//                        .setBody(new StringBody(String.valueOf(chunkNo), ContentType.APPLICATION_FORM_URLENCODED))
//                        .build())
//                .addPart(FormBodyPartBuilder.create()
//                        .setName("chunks")
//                        .setBody(new StringBody(String.valueOf(totalChunks), ContentType.APPLICATION_FORM_URLENCODED))
//                        .build())
//                .addPart(FormBodyPartBuilder.create()
//                        .setName("md5")
//                        .setBody(new StringBody(md5Str, ContentType.APPLICATION_FORM_URLENCODED))
//                        .build())
//                .addPart(FormBodyPartBuilder.create()
//                        .setName("file")
//                        .setBody(new ByteArrayBody(bytes, ContentType.APPLICATION_OCTET_STREAM, targetFile.getName()))
//                        .build())
//                .build();
//
//        String path = targetFile.getAbsolutePath();
//        for (int i = 0; i < RETRY_COUNT; i++) {
//            try {
//                Thread.sleep(CHUNK_RETRY_DELAY);
//                String respStr = HttpClientUtil.sendPost(uploadUrl, null, requestEntity, false);
//                JSONObject respObj = JSONObject.parseObject(respStr);
//                if (Objects.equals(respObj.getString("info"), "Successful.")) {
//                    log.info("chunk upload success, file: {}, progress: {}/{}, time cost: {}s.", path, chunkShowNo, totalChunks,
//                            (System.currentTimeMillis() - startTime) / 1000);
//                    return true;
//                } else {
//                    log.error("{}th chunk upload fail, file: {}, ret: {}, retry: {}/{}", chunkShowNo, targetFile.getAbsoluteFile(), respStr, i + 1, RETRY_COUNT);
//                }
//            } catch (Exception e) {
//                log.error("{}th chunk upload error, file: {}, retry: {}/{}", chunkShowNo, path, i + 1, RETRY_COUNT, e);
//            }
//        }
//        return false;
//    }
//
//
//    public boolean finishChunks(String finishUrl, int totalChunks, String videoName, File videoFile) throws Exception {
//        HttpEntity completeEntity = MultipartEntityBuilder.create()
//                .addPart(FormBodyPartBuilder.create()
//                        .setName("version")
//                        .setBody(new StringBody("2.3.0.1088", ContentType.APPLICATION_FORM_URLENCODED))
//                        .build())
//                .addPart(FormBodyPartBuilder.create()
//                        .setName("filesize")
//                        .setBody(new StringBody(String.valueOf(FileUtil.size(videoFile)),
//                                ContentType.APPLICATION_FORM_URLENCODED))
//                        .build())
//                .addPart(FormBodyPartBuilder.create()
//                        .setName("chunks")
//                        .setBody(new StringBody(String.valueOf(totalChunks), ContentType.APPLICATION_FORM_URLENCODED))
//                        .build())
//                .addPart(FormBodyPartBuilder.create()
//                        .setName("md5")
//                        .setBody(new StringBody(
//                                DigestUtils.md5Hex(Files.newInputStream(Paths.get(videoFile.getAbsolutePath()))),
//                                ContentType.APPLICATION_FORM_URLENCODED))
//                        .build())
//                .addPart(FormBodyPartBuilder.create()
//                        .setName("name")
//                        .setBody(new StringBody(videoName, ContentType.APPLICATION_FORM_URLENCODED))
//                        .build())
//                .build();
//
//        for (int i = 0; i < RETRY_COUNT; i++) {
//            try {
//                Thread.sleep(CHUNK_RETRY_DELAY);
//                String completeResp = HttpClientUtil.sendPost(finishUrl, null, completeEntity);
//                JSONObject respObj = JSONObject.parseObject(completeResp);
//                if (Objects.equals(respObj.getString("OK"), "1")) {
//                    log.info("complete upload success, videoName: {}", videoName);
//                    return true;
//                } else {
//                    log.error("complete upload fail, videoName: {}, retry: {}/{}.", videoName, i + 1, RETRY_COUNT);
//                }
//            } catch (Exception e) {
//                log.error("complete upload error, retry: {}/{}", i + 1, RETRY_COUNT, e);
//            }
//        }
//        return false;
//    }
//
//
//    private boolean postWork(List<RemoteSeverVideo> remoteSeverVideos, String recordPath) {
//        if (CollectionUtils.isEmpty(remoteSeverVideos)) {
//            return false;
//        }
//
//        String accessToken = ConfigFetcher.getInitConfig().getAccessToken();
//        String postWorkUrl = String.format(CLIENT_POST_VIDEO_URL, accessToken);
//        for (int i = 0; i < RETRY_COUNT; i++) {
//            try {
//                Thread.sleep(CHUNK_RETRY_DELAY);
//                String resp = HttpClientUtil.sendPost(postWorkUrl, CLIENT_HEADERS,
//                        buildPostWorkParamOnClient(remoteSeverVideos, recordPath));
//                JSONObject respObj = JSONObject.parseObject(resp);
//                if (Objects.equals(respObj.getString("code"), "0")) {
//                    log.info("postWork success, video is uploaded, recordPath: {}", recordPath);
//                    return true;
//                } else {
//                    log.error("postWork failed, res: {}, title: {}, retry: {}/{}.", resp, JSON.toJSONString(remoteSeverVideos), i + 1, RETRY_COUNT);
//                }
//            } catch (Exception e) {
//                log.error("postWork error, retry: {}/{}", i + 1, RETRY_COUNT, e);
//            }
//        }
//        return false;
//    }
//
//    private JSONObject buildPostWorkParamOnClient(List<RemoteSeverVideo> remoteSeverVideos, String recordPath) {
//        String streamerName = StreamerInfoHolder.getCurStreamerName();
//        StreamerConfig streamerConfig = ConfigFetcher.getStreamerInfoByName(streamerName);
//        BiliWorkMetaData workMetaData = (BiliWorkMetaData) new UploaderFactory.BiliMetaDataBuilder().buildMetaData(streamerConfig, recordPath);
//
//        List<JSONObject> videoObjs = Lists.newArrayList();
//        for (RemoteSeverVideo remoteSeverVideo : remoteSeverVideos) {
//            JSONObject videoObj = new JSONObject();
//            videoObj.put("title", FileUtil.getPrefix(remoteSeverVideo.getLocalFilePath()));
//            videoObj.put("filename", remoteSeverVideo.getServerFileName());
//            videoObjs.add(videoObj);
//        }
//        String thumbnailUrl = uploadThumbnail(recordPath);
//
//        JSONObject params = new JSONObject();
//        params.put("cover", StringUtils.isBlank(thumbnailUrl) ? workMetaData.getCover() : thumbnailUrl);
//        params.put("build", 1088);
//        params.put("title", workMetaData.getTitle());
//        params.put("tid", workMetaData.getTid());
//        params.put("tag", StringUtils.join(workMetaData.getTags(), ","));
//        params.put("desc", workMetaData.getDesc());
//        params.put("dynamic", workMetaData.getDynamic());
//        params.put("copyright", 1);
//        params.put("source", workMetaData.getSource());
//        params.put("videos", videoObjs);
//        params.put("no_reprint", 0);
//        params.put("open_elec", 1);
//
//        return params;
//    }
//
//    /**
//     * 上传视频封面
//     *
//     * @param recordPath
//     * @return
//     */
//    private String uploadThumbnail(String recordPath) {
//        File file = new File(recordPath, RecordConstant.THUMBNAIL_FILE_NAME);
//        if (!file.exists()) {
//            return null;
//        }
//
//        try (InputStream inputStream = Files.newInputStream(file.toPath())) {
//            byte[] bytes = new byte[(int) file.length()];
//            inputStream.read(bytes);
//
//            // 上传封面
//            String accessToken = ConfigFetcher.getInitConfig().getAccessToken();
//            String coverUploadUrl = String.format(CLIENT_COVER_UPLOAD_URL, accessToken);
//            HttpEntity requestEntity = MultipartEntityBuilder.create()
//                    .addPart(FormBodyPartBuilder.create()
//                            .setName("file")
//                            .setBody(new ByteArrayBody(bytes, ContentType.IMAGE_PNG, "cover.png"))
//                            .build())
//                    .build();
//            String respStr = HttpClientUtil.sendPost(coverUploadUrl, null, requestEntity, true);
//            JSONObject respObj = JSONObject.parseObject(respStr);
//            return respObj.getJSONObject("data").getString("url");
//        } catch (Exception e) {
//            log.error("upload thumbnail failed,recordPath: {}", recordPath, e);
//            return null;
//        }
//    }
//}
