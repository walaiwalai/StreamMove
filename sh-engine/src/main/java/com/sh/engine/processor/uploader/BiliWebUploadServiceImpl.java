//package com.sh.engine.upload;
//
//import cn.hutool.core.util.URLUtil;
//import com.alibaba.fastjson.JSON;
//import com.alibaba.fastjson.JSONObject;
//import com.google.common.collect.Lists;
//import com.sh.config.exception.ErrorEnum;
//import com.sh.config.exception.StreamerRecordException;
//import com.sh.config.manager.ConfigFetcher;
//import com.sh.config.model.config.StreamerConfig;
//import com.sh.config.model.stauts.FileStatusModel;
//import com.sh.config.model.video.FailUploadVideoChunk;
//import com.sh.config.model.video.LocalVideo;
//import com.sh.config.model.video.RemoteSeverVideo;
//import com.sh.config.model.video.UploadVideoPair;
//import com.sh.config.utils.VideoFileUtil;
//import com.sh.engine.constant.UploadPlatformEnum;
//import com.sh.engine.base.StreamerInfoHolder;
//import com.sh.engine.constant.RecordConstant;
//import com.sh.engine.model.bili.BiliWebPreUploadCommand;
//import com.sh.engine.model.bili.BiliWebPreUploadParams;
//import com.sh.engine.model.bili.web.VideoUploadResultModel;
//import com.sh.engine.model.upload.BaseUploadTask;
//import com.sh.message.service.MsgSendService;
//import lombok.extern.slf4j.Slf4j;
//import okhttp3.*;
//import org.apache.commons.collections4.CollectionUtils;
//import org.apache.commons.lang3.StringUtils;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.stereotype.Component;
//
//import java.io.File;
//import java.io.IOException;
//import java.util.*;
//import java.util.concurrent.CompletableFuture;
//import java.util.concurrent.CountDownLatch;
//import java.util.stream.Collectors;
//
///**
// * @Author caiwen
// * @Date 2024 01 05 21 27
// **/
//@Slf4j
//@Component
//public class BiliWebUploadServiceImpl extends AbstractWorkUploadService {
//    @Autowired
//    private MsgSendService msgSendService;
//    private static final OkHttpClient CLIENT = new OkHttpClient();
//    public static final Integer CHUNK_RETRY = 3;
//    public static final Integer CHUNK_RETRY_DELAY = 500;
//
//    @Override
//    public String getName() {
//        return UploadPlatformEnum.BILI_WEB.getType();
//    }
//
//    @Override
//    public boolean upload(List<LocalVideo> localVideos, BaseUploadTask task) throws Exception {
//        String dirName = task.getDirName();
//
//        // 1. 多个视频分P进行上传
//        FileStatusModel fileStatus = FileStatusModel.loadFromFile(dirName);
//        UploadVideoPair videoParts = fileStatus.fetchVideoPartByPlatform(getName());
//        List<RemoteSeverVideo> remoteVideos = Optional.ofNullable(videoParts)
//                .map(UploadVideoPair::getSucceedUploadedVideos)
//                .orElse(Lists.newArrayList())
//                .stream()
//                .map(succeedUploadSeverVideo -> (RemoteSeverVideo) succeedUploadSeverVideo)
//                .collect(Collectors.toList());
//
//        BiliWebPreUploadParams biliPreUploadInfo = null;
//        for (int i = 0; i < localVideos.size(); i++) {
//            LocalVideo localVideo = localVideos.get(i);
//            if (localVideo.isUpload()) {
//                continue;
//            }
//
//            // 1.0 进行预上传
//            BiliWebPreUploadCommand command = new BiliWebPreUploadCommand(new File(localVideo.getLocalFileFullPath()));
//            command.doWebPreUp();
//            biliPreUploadInfo = command.getBiliWebPreUploadParams();
//
//            // 1.1 上传单个视频
//            VideoUploadResultModel biliVideoUploadResult = uploadOnWeb(localVideo, biliPreUploadInfo);
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
//            remoteVideos.add(biliVideoUploadResult.getRemoteSeverVideo());
//
//            // 1.2 发消息
//            msgSendService.sendText(localVideo.getLocalFileFullPath() + "路径下的视频上传成功！");
//        }
//
//        // 2. 给需要上传的视频文件命名
//        for (int i = 0; i < remoteVideos.size(); i++) {
//            remoteVideos.get(i).setTitle("P" + (i + 1));
//        }
//
//        boolean isPostSuccess = postWork(remoteVideos, task, biliPreUploadInfo);
//        if (!isPostSuccess) {
//            throw new StreamerRecordException(ErrorEnum.POST_WORK_ERROR);
//        }
//
//        log.info("upload video success.");
//
//        return true;
//    }
//
//    public VideoUploadResultModel uploadOnWeb(LocalVideo localVideo, BiliWebPreUploadParams biliPreUploadInfo) throws Exception {
//        File videoFile = new File(localVideo.getLocalFileFullPath());
//        String videoName = videoFile.getName();
//        VideoUploadResultModel uploadResult = new VideoUploadResultModel();
//
//        // 2.进行视频分块上传
//        Integer chunkSize = biliPreUploadInfo.getChunkSize();
//        long fileSize = localVideo.getFileSize();
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
//                                .replace("{uploadUrl}", biliPreUploadInfo.getUploadUrl())
//                                .replace("{partNumber}", String.valueOf(finalI + 1))
//                                .replace("{uploadId}", biliPreUploadInfo.getUploadId())
//                                .replace("{chunk}", String.valueOf(finalI))
//                                .replace("{chunks}", String.valueOf(partCount))
//                                .replace("{size}", String.valueOf(curChunkSize))
//                                .replace("{start}", String.valueOf(curChunkStart))
//                                .replace("{end}", String.valueOf(curChunkEnd))
//                                .replace("{total}", String.valueOf(fileSize));
//                        return uploadChunk(chunkUploadUrl, videoFile, finalI, partCount, (int) curChunkSize, curChunkStart, biliPreUploadInfo);
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
//        countDownLatch.await();
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
//        boolean isFinish = finishChunks(finishUrl, partCount, biliPreUploadInfo);
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
//
//    private boolean uploadChunk(String uploadUrl, File targetFile, Integer chunkNo, Integer totalChunks, Integer curChunkSize, Long curChunkStart, BiliWebPreUploadParams preUploadParams) {
//        int chunkShowNo = chunkNo + 1;
//        log.info("start to upload {}th video chunk, start: {}, curSize: {}, curChunkSize: {}M.", chunkShowNo,
//                curChunkStart, curChunkSize, curChunkSize / 1024 / 1024);
//        long startTime = System.currentTimeMillis();
//
//        byte[] bytes = null;
//        try {
//            bytes = VideoFileUtil.fetchBlock(targetFile, curChunkStart, curChunkSize);
//        } catch (IOException e) {
//            log.error("fetch chunk error, file: {}, chunkNo: {}, start: {}, curSize: {}", targetFile.getAbsolutePath(),
//                    chunkShowNo, curChunkStart, curChunkSize, e);
//        }
//
//        RequestBody chunkRequestBody = RequestBody
//                .create(MediaType.parse("application/octet-stream"), bytes);
//        Request request = new Request.Builder()
//                .url(uploadUrl)
//                .addHeader("Accept", "*/*")
//                .addHeader("Accept-Encoding", "deflate")
//                .addHeader("content-type", "application/octet-stream")
//                .addHeader("user-agent", "Mozilla/5.0 (Windows NT 10.0; WOW64) Chrome/63.0.3239.132 Safari/537.36")
//                .addHeader("origin", "https://member.bilibili.com")
//                .addHeader("referer", "https://member.bilibili.com/platform/upload/video/frame")
//                .addHeader("sec-fetch-mode", "cors")
//                .addHeader("sec-fetch-site", "cross-site")
//                .addHeader("cookie", ConfigFetcher.getInitConfig().getBiliCookies())
//                .addHeader("x-upos-auth", preUploadParams.getAuth())
//                .put(chunkRequestBody)
//                .build();
//        for (int i = 0; i < CHUNK_RETRY; i++) {
//            try (Response response = CLIENT.newCall(request).execute()) {
//                Thread.sleep(CHUNK_RETRY_DELAY);
//                if (response.isSuccessful()) {
//                    log.info("chunk upload success, progress: {}/{}, time cost: {}s.", chunkShowNo, totalChunks,
//                            (System.currentTimeMillis() - startTime) / 1000);
//                    return true;
//                } else {
//                    String message = response.message();
//                    String bodyStr = response.body() != null ? response.body().string() : null;
//                    log.error("the {}th chunk upload failed, will retry... message: {}, bodyStr: {}", chunkShowNo, message, bodyStr);
//                }
//            } catch (Exception e) {
//                log.error("th {}th chunk upload error, will retry...", chunkShowNo, e);
//            }
//        }
//        return false;
//    }
//
//    private boolean finishChunks(String finishUrl, int totalChunks, BiliWebPreUploadParams biliPreUploadInfo) throws Exception {
//        List<Map<String, String>> parts = Lists.newArrayList();
//        for (int i = 0; i < totalChunks; i++) {
//            HashMap map = new HashMap();
//            map.put("partNumber", i + 1);
//            map.put("eTag", "etag");
//            parts.add(map);
//        }
//        JSONObject params = new JSONObject();
//        params.put("parts", parts);
//
//        MediaType mediaType = MediaType.parse("application/json");
//        RequestBody requestBody = RequestBody.create(mediaType, JSON.toJSONString(params));
//        Request request = new Request.Builder()
//                .url(finishUrl)
//                .addHeader("Accept", "*/*")
//                .addHeader("Accept-Encoding", "deflate")
//                .addHeader("content-type", "application/octet-stream")
//                .addHeader("user-agent", "Mozilla/5.0 (Windows NT 10.0; WOW64) Chrome/63.0.3239.132 Safari/537.36")
//                .addHeader("origin", "https://member.bilibili.com")
//                .addHeader("referer", "https://member.bilibili.com/platform/upload/video/frame")
//                .addHeader("sec-fetch-mode", "cors")
//                .addHeader("sec-fetch-site", "cross-site")
//                .addHeader("cookie", ConfigFetcher.getInitConfig().getBiliCookies())
//                .addHeader("x-upos-auth", biliPreUploadInfo.getAuth())
//                .post(requestBody)
//                .build();
//
//        try (Response response = CLIENT.newCall(request).execute()) {
//            if (response.isSuccessful()) {
//                String resp = response.body().string();
//                String ok = JSON.parseObject(resp).getString("OK");
//                return Objects.equals(ok, "1");
//            } else {
//                String message = response.message();
//                String bodyStr = response.body() != null ? response.body().string() : null;
//                log.error("finish chunks failed, message: {}, bodyStr: {}", message, bodyStr);
//            }
//        } catch (IOException e) {
//            log.error("finish chunks error", e);
//        }
//        return false;
//    }
//
//    public boolean postWork(List<RemoteSeverVideo> remoteSeverVideos, BaseUploadTask task, BiliWebPreUploadParams preInfo) {
//        StreamerConfig streamerConfig = ConfigFetcher.getStreamerInfoByName(StreamerInfoHolder.getCurStreamerName());
//
//        String postWorkUrl = RecordConstant.BILI_POST_WORK
//                .replace("{t}", String.valueOf(System.currentTimeMillis()))
//                .replace("{csrf}", fetchCsrf(ConfigFetcher.getInitConfig().getBiliCookies()));
//
//        JSONObject params = buildPostWorkParam(streamerConfig, remoteSeverVideos, task);
//        MediaType mediaType = MediaType.parse("application/json");
//        RequestBody requestBody = RequestBody.create(mediaType, JSON.toJSONString(params));
//
//        Request request = new Request.Builder()
//                .url(postWorkUrl)
//                .addHeader("Accept", "*/*")
//                .addHeader("Accept-Encoding", "deflate")
//                .addHeader("content-type", "application/octet-stream")
//                .addHeader("user-agent", "Mozilla/5.0 (Windows NT 10.0; WOW64) Chrome/63.0.3239.132 Safari/537.36")
//                .addHeader("origin", "https://member.bilibili.com")
//                .addHeader("referer", "https://member.bilibili.com/platform/upload/video/frame")
//                .addHeader("sec-fetch-mode", "cors")
//                .addHeader("sec-fetch-site", "cross-site")
//                .addHeader("cookie", ConfigFetcher.getInitConfig().getBiliCookies())
//                .addHeader("x-upos-auth", preInfo.getAuth())
//                .post(requestBody)
//                .build();
//
//        String title = task.getTitle();
//        try (Response response = CLIENT.newCall(request).execute()) {
//            if (response.isSuccessful()) {
//                String resp = response.body().string();
//                String code = JSON.parseObject(resp).getString("code");
//                if (Objects.equals(code, "0")) {
//                    log.info("postWork success, video is upload, title: {}", title);
//                    return true;
//                } else {
//                    log.error("postWork failed, res: {}, title: {}", resp, title);
//                    return false;
//                }
//            } else {
//                String message = response.message();
//                String bodyStr = response.body() != null ? response.body().string() : null;
//                log.error("postWork failed, message: {}, bodyStr: {}", message, bodyStr);
//            }
//        } catch (IOException e) {
//            log.error("finish chunks error", e);
//        }
//        return false;
//    }
//
//    private String fetchCsrf(String biliCookies) {
//        return StringUtils.substringBetween(biliCookies, "bili_jct=", ";");
//    }
//
//    private JSONObject buildPostWorkParam(StreamerConfig streamerConfig, List<RemoteSeverVideo> remoteSeverVideos,
//                                          BaseUploadTask task) {
//        JSONObject params = new JSONObject();
//        params.put("cover", streamerConfig.getCover());
//        params.put("title", task.getTitle());
//        params.put("tid", streamerConfig.getTid());
//        params.put("tag", StringUtils.join(streamerConfig.getTags(), ","));
//        params.put("desc", streamerConfig.getDesc());
//        params.put("dynamic", streamerConfig.getDynamic());
//        params.put("copyright", 1);
//        params.put("source", streamerConfig.getSource());
//        params.put("videos", remoteSeverVideos);
//        params.put("no_reprint", 0);
//        params.put("open_elec", 0);
//        params.put("csrf", fetchCsrf(ConfigFetcher.getInitConfig().getBiliCookies()));
//
//        return params;
//    }
//}
