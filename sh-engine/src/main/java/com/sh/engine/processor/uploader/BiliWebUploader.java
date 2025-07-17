package com.sh.engine.processor.uploader;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.URLUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.TypeReference;
import com.google.common.collect.Lists;
import com.sh.config.exception.ErrorEnum;
import com.sh.config.exception.StreamerRecordException;
import com.sh.config.manager.ConfigFetcher;
import com.sh.config.model.config.StreamerConfig;
import com.sh.config.model.video.RemoteSeverVideo;
import com.sh.config.utils.*;
import com.sh.engine.base.StreamerInfoHolder;
import com.sh.engine.constant.RecordConstant;
import com.sh.engine.constant.UploadPlatformEnum;
import com.sh.engine.model.bili.BiliWebPreUploadCommand;
import com.sh.engine.model.bili.BiliWebPreUploadParams;
import com.sh.engine.processor.uploader.meta.BiliWorkMetaData;
import com.sh.engine.service.process.VideoMergeService;
import com.sh.message.service.MsgSendService;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.FileFilterUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * @Author caiwen
 * @Date 2024 01 05 21 27
 **/
@Slf4j
@Component
public class BiliWebUploader extends Uploader {
    @Resource
    private MsgSendService msgSendService;
    @Resource
    private VideoMergeService videoMergeService;

    private static final OkHttpClient CLIENT = new OkHttpClient();
    public static final Integer CHUNK_RETRY = 10;
    public static final Integer CHUNK_RETRY_DELAY = 500;

    @Override
    public String getType() {
        return UploadPlatformEnum.BILI_WEB.getType();
    }

    @Override
    public void setUp() {

    }

    @Override
    public boolean upload(String recordPath) throws Exception {
        // 0. 获取要上传的文件
        List<File> localVideos = fetchUploadVideos(recordPath);
        if (CollectionUtils.isEmpty(localVideos)) {
            return true;
        }

        // 1. 上传视频
        List<RemoteSeverVideo> remoteVideos = Lists.newArrayList();
        for (int i = 0; i < localVideos.size(); i++) {
            File localVideo = localVideos.get(i);
            RemoteSeverVideo uploadedVideo = getUploadedVideo(localVideo);
            if (uploadedVideo != null) {
                log.info("video has been uploaded, will skip, path: {}", localVideo.getAbsolutePath());
                remoteVideos.add(uploadedVideo);
                continue;
            }

            // 1.1 上传单个视频
            uploadedVideo = uploadOnWeb(localVideo);
            if (uploadedVideo == null) {
                // 上传失败，发送作品为空均视为失败
                msgSendService.sendText(localVideo.getAbsolutePath() + "路径下的视频上传B站失败！");
                throw new StreamerRecordException(ErrorEnum.UPLOAD_CHUNK_ERROR);
            }

            saveUploadedVideo(uploadedVideo);
            remoteVideos.add(uploadedVideo);

            // 1.2 发消息
            msgSendService.sendText(localVideo.getAbsolutePath() + "路径下的视频上传B站成功！");
        }

        // 2. 提交作品
        boolean isPostSuccess = postWork(remoteVideos, recordPath);
        if (!isPostSuccess) {
            throw new StreamerRecordException(ErrorEnum.POST_WORK_ERROR);
        }

        // 3. 提交成功清理一下缓存
        clearUploadedVideos();

        return true;
    }

    @Override
    public void preProcess(String recordPath) {
        // 特殊逻辑： 如果带有b站片头视频，再合成一份新的视频
        String streamerName = StreamerInfoHolder.getCurStreamerName();
        StreamerConfig streamerConfig = ConfigFetcher.getStreamerInfoByName(streamerName);
        List<String> biliOpeningAnimations = streamerConfig.getBiliOpeningAnimations();
        File highlightTmpDir = new File(recordPath, "tmp-h");
        if (CollectionUtils.isEmpty(biliOpeningAnimations) || !highlightTmpDir.exists()) {
            return;
        }

        // 目标合成视频
        File exclusiveDir = new File(recordPath, getType());
        if (!exclusiveDir.exists()) {
            exclusiveDir.mkdirs();
        }

        File targetFile = new File(exclusiveDir, RecordConstant.LOL_HL_VIDEO);
        if (targetFile.exists()) {
            return;
        }

        // 根据streamerName的hash随机取BiliOpeningAnimations的片头
        int index = Math.abs(recordPath.hashCode() % biliOpeningAnimations.size());
        String biliOpeningAnimation = biliOpeningAnimations.get(index);
        List<String> localFps = FileUtils.listFiles(highlightTmpDir, FileFilterUtils.suffixFileFilter("ts"), null)
                .stream()
                .sorted(Comparator.comparingLong(File::lastModified))
                .map(File::getAbsolutePath)
                .collect(Collectors.toList());
        int insertIndex = 0;
        localFps.add(insertIndex, biliOpeningAnimation);


        // 合并视频片头
        boolean success = videoMergeService.concatDiffVideos(localFps, targetFile);
        String msgPrefix = success ? "合并视频片头完成！路径为：" : "合并视频片头失败！路径为：";
        msgSendService.sendText(msgPrefix + targetFile.getAbsolutePath());
    }

    /**
     * 获取要上传的视频
     *
     * @param recordPath 文件地址
     * @return 要上传的视频
     */
    private List<File> fetchUploadVideos(String recordPath) {
        long videoPartLimitSize = ConfigFetcher.getInitConfig().getVideoPartLimitSize() * 1024L * 1024L;

        // 遍历本地的视频文件
        List<File> allVideos = FileUtils.listFiles(new File(recordPath), FileFilterUtils.suffixFileFilter("mp4"), null)
                .stream()
                .sorted(Comparator.comparingLong(File::lastModified))
                .filter(file -> FileUtil.size(file) >= videoPartLimitSize)
                .collect(Collectors.toList());


        // 专属文件夹(优先替换)
        File exclusiveDir = new File(recordPath, getType());
        Collection<File> exclusiveFiles;
        if (exclusiveDir.exists()) {
            exclusiveFiles = FileUtils.listFiles(exclusiveDir, FileFilterUtils.suffixFileFilter("mp4"), null);
        } else {
            exclusiveFiles = Collections.emptyList();
        }
        Map<String, File> exclusiveFileMap = exclusiveFiles.stream().collect(Collectors.toMap(File::getName, Function.identity(), (v1, v2) -> v2));
        List<File> res = Lists.newArrayList();
        for (File video : allVideos) {
            File addVideo = exclusiveFileMap.getOrDefault(video.getName(), video);
            res.add(addVideo);
            log.info("Final added video: {}", addVideo.getAbsolutePath());
        }
        return res;
    }

    public RemoteSeverVideo uploadOnWeb(File videoFile) throws Exception {
        BiliWebPreUploadCommand command = new BiliWebPreUploadCommand(videoFile);
        command.doWebPreUp();
        BiliWebPreUploadParams biliPreUploadInfo = command.getBiliWebPreUploadParams();

        Integer chunkSize = biliPreUploadInfo.getChunkSize();
        String uploadUrl = biliPreUploadInfo.getUploadUrl();
        String uploadId = biliPreUploadInfo.getUploadId();

        String videoName = videoFile.getName();
        long fileSize = FileUtil.size(videoFile);

        // 2.进行视频分块上传
        int partCount = (int) Math.ceil(fileSize * 1.0 / chunkSize);
        log.info("video size is {}M, seg {} parts to upload.", fileSize / 1024 / 1024, partCount);
        CountDownLatch countDownLatch = new CountDownLatch(partCount);
        List<Integer> failChunkNums = Lists.newCopyOnWriteArrayList();

        // 保证记录的顺序和实际上传的顺序一致
        LinkedBlockingQueue<Integer> completedPartsQueue = new LinkedBlockingQueue<>();
        AtomicBoolean hasFailed = new AtomicBoolean(false);

        for (int i = 0; i < partCount; i++) {
            long curChunkStart = (long) i * chunkSize;
            long curChunkSize = (i + 1 == partCount) ? (fileSize - curChunkStart) : chunkSize;
            long curChunkEnd = curChunkStart + curChunkSize;

            int finalI = i;
            CompletableFuture.supplyAsync(() -> {
                        String chunkUploadUrl = RecordConstant.BILI_VIDEO_CHUNK_UPLOAD_URL
                                .replace("{uploadUrl}", uploadUrl)
                                .replace("{partNumber}", String.valueOf(finalI + 1))
                                .replace("{uploadId}", uploadId)
                                .replace("{chunk}", String.valueOf(finalI))
                                .replace("{chunks}", String.valueOf(partCount))
                                .replace("{size}", String.valueOf(curChunkSize))
                                .replace("{start}", String.valueOf(curChunkStart))
                                .replace("{end}", String.valueOf(curChunkEnd))
                                .replace("{total}", String.valueOf(fileSize));
                        return uploadChunk(chunkUploadUrl, videoFile, finalI, partCount, (int) curChunkSize, curChunkStart, biliPreUploadInfo);
                    }, ExecutorPoolUtil.getUploadPool())
                    .whenComplete((isSuccess, throwbale) -> {
                        try {
                            if (isSuccess) {
                                // 按完成顺序入队
                                completedPartsQueue.put(finalI + 1);
                            } else {
                                if (hasFailed.compareAndSet(false, true)) {
                                    failChunkNums.add(finalI);
                                }
                            }
                        } catch (Exception e) {
                            log.error("fuck uploading chunks", e);
                        } finally {
                            countDownLatch.countDown();
                        }
                    });
        }
        countDownLatch.await();


        if (CollectionUtils.isEmpty(failChunkNums)) {
            log.info("video chunks upload success, videoPath: {}", videoFile.getAbsolutePath());
        } else {
            log.error("video chunks upload fail, failed chunkNos: {}", JSON.toJSONString(failChunkNums));
            return null;
        }

        // 3. 完成分块的上传
        // 按完成顺序提取块号
        List<Integer> completedPartNumbers = Lists.newArrayList();
        Integer partNumber;
        while ((partNumber = completedPartsQueue.poll()) != null) {
            completedPartNumbers.add(partNumber);
        }

        log.info("video chunks upload finish, completedPartNumbers: {}", JSON.toJSONString(completedPartNumbers));
        String finishUrl = String.format(RecordConstant.BILI_CHUNK_UPLOAD_FINISH_URL,
                uploadUrl, URLUtil.encode(videoName), uploadId, biliPreUploadInfo.getBizId());
        boolean isFinish = finishChunks(finishUrl, biliPreUploadInfo, completedPartNumbers);
        if (!isFinish) {
            return null;
        }

        // 4. 组装服务器端的视频
        String uposUri = biliPreUploadInfo.getUposUri();
        String[] tmps = uposUri.split("//")[1].split("/");
        String fileNameOnServer = tmps[tmps.length - 1].split(".mp4")[0];
        return new RemoteSeverVideo(fileNameOnServer, videoFile.getAbsolutePath());
    }


    private boolean uploadChunk(String uploadUrl, File targetFile, Integer chunkNo, Integer totalChunks, Integer curChunkSize, Long curChunkStart, BiliWebPreUploadParams preUploadParams) {
        int chunkShowNo = chunkNo + 1;
        long startTime = System.currentTimeMillis();

        byte[] bytes = null;
        try {
            bytes = VideoFileUtil.fetchBlock(targetFile, curChunkStart, curChunkSize);
        } catch (IOException e) {
            log.error("fetch chunk error, file: {}, chunkNo: {}, start: {}, curSize: {}", targetFile.getAbsolutePath(),
                    chunkShowNo, curChunkStart, curChunkSize, e);
        }

        RequestBody chunkRequestBody = RequestBody
                .create(MediaType.parse("application/octet-stream"), bytes);
        Request request = new Request.Builder()
                .url(uploadUrl)
                .addHeader("Accept", "*/*")
                .addHeader("Accept-Encoding", "gzip, deflate, br, zstd")
                .addHeader("user-agent", "Mozilla/5.0 (Windows NT 10.0; WOW64) Chrome/63.0.3239.132 Safari/537.36")
                .addHeader("origin", "https://member.bilibili.com")
                .addHeader("referer", "https://member.bilibili.com/platform/upload/video/frame")
                .addHeader("sec-fetch-mode", "cors")
                .addHeader("sec-fetch-site", "cross-site")
                .addHeader("cookie", ConfigFetcher.getInitConfig().getBiliCookies())
                .addHeader("x-upos-auth", preUploadParams.getAuth())
                .put(chunkRequestBody)
                .build();

        String path = targetFile.getAbsolutePath();
        for (int i = 0; i < CHUNK_RETRY; i++) {
            try {
                if (i > 0) {
                    Thread.sleep(CHUNK_RETRY_DELAY);
                }
                String resp = OkHttpClientUtil.execute(request);
                log.info("chunk upload success, file: {}, progress: {}/{}, time cost: {}s.", path, chunkShowNo, totalChunks,
                        (System.currentTimeMillis() - startTime) / 1000);
                return true;
            } catch (Exception e) {
                log.error("th {}th chunk upload error, retry: {}/{}", chunkShowNo, i + 1, CHUNK_RETRY, e);
            }
        }
        return false;
    }

    private boolean finishChunks(String finishUrl, BiliWebPreUploadParams biliPreUploadInfo, List<Integer> completedPartNumbers) {
        List<Map<String, String>> parts = Lists.newArrayList();
        // 这个的顺序是实际上传的顺序
        for (Integer partNumber : completedPartNumbers) {
            HashMap map = new HashMap();
            map.put("partNumber", partNumber);
            map.put("eTag", "etag");
            parts.add(map);
        }
        JSONObject params = new JSONObject();
        params.put("parts", parts);

        MediaType mediaType = MediaType.parse("application/json");
        RequestBody requestBody = RequestBody.create(mediaType, JSON.toJSONString(params));
        Request request = new Request.Builder()
                .url(finishUrl)
                .addHeader("Accept", "*/*")
                .addHeader("Accept-Encoding", "deflate")
                .addHeader("content-type", "application/octet-stream")
                .addHeader("user-agent", "Mozilla/5.0 (Windows NT 10.0; WOW64) Chrome/63.0.3239.132 Safari/537.36")
                .addHeader("origin", "https://member.bilibili.com")
                .addHeader("referer", "https://member.bilibili.com/platform/upload/video/frame")
                .addHeader("sec-fetch-mode", "cors")
                .addHeader("sec-fetch-site", "cross-site")
                .addHeader("cookie", ConfigFetcher.getInitConfig().getBiliCookies())
                .addHeader("x-upos-auth", biliPreUploadInfo.getAuth())
                .post(requestBody)
                .build();

        try (Response response = CLIENT.newCall(request).execute()) {
            if (response.isSuccessful()) {
                String resp = response.body().string();
                String ok = JSON.parseObject(resp).getString("OK");
                return Objects.equals(ok, "1");
            } else {
                String message = response.message();
                String bodyStr = response.body() != null ? response.body().string() : null;
                log.error("finish chunks failed, message: {}, bodyStr: {}", message, bodyStr);
            }
        } catch (IOException e) {
            log.error("finish chunks error", e);
        }
        return false;
    }

    public boolean postWork(List<RemoteSeverVideo> remoteSeverVideos, String recordPath) {
        String postWorkUrl = RecordConstant.BILI_POST_WORK
                .replace("{t}", String.valueOf(System.currentTimeMillis()))
                .replace("{csrf}", fetchCsrf());

        JSONObject params = buildPostWorkParamOnWeb(remoteSeverVideos, recordPath);
        MediaType mediaType = MediaType.parse("application/json");
        RequestBody requestBody = RequestBody.create(mediaType, JSON.toJSONString(params));

        Request request = new Request.Builder()
                .url(postWorkUrl)
                .addHeader("Accept", "*/*")
                .addHeader("Accept-Encoding", "deflate")
                .addHeader("content-type", "application/octet-stream")
                .addHeader("user-agent", "Mozilla/5.0 (Windows NT 10.0; WOW64) Chrome/63.0.3239.132 Safari/537.36")
                .addHeader("origin", "https://member.bilibili.com")
                .addHeader("referer", "https://member.bilibili.com/platform/upload/video/frame")
                .addHeader("sec-fetch-mode", "cors")
                .addHeader("sec-fetch-site", "cross-site")
                .addHeader("cookie", ConfigFetcher.getInitConfig().getBiliCookies())
//                .addHeader("x-upos-auth", preInfo.getAuth())
                .post(requestBody)
                .build();

        for (int i = 0; i < CHUNK_RETRY; i++) {
            try {
                if (i > 0) {
                    Thread.sleep(CHUNK_RETRY_DELAY);
                }
                String resp = OkHttpClientUtil.execute(request);
                String code = JSON.parseObject(resp).getString("code");
                if (Objects.equals(code, "0")) {
                    log.info("postWork success, video is uploaded, recordPath: {}", recordPath);
                    return true;
                } else {
                    log.error("postWork failed, res: {}, title: {}, retry: {}/{}.", resp, JSON.toJSONString(remoteSeverVideos), i + 1, CHUNK_RETRY);
                }
                return true;
            } catch (Exception e) {
                log.error("postWork error, retry: {}/{}", i + 1, CHUNK_RETRY, e);
            }
        }

        return false;
    }

    private String fetchCsrf() {
//        String biliCookies = ConfigFetcher.getInitConfig().getBiliCookies();
        String biliCookies = "buvid3=61A174A7-52DA-7505-D1A4-7AE52E8508A102096infoc; b_nut=1752410402; _uuid=8BA81010BE-4167-1DC5-5A87-56B49AF108B3302553infoc; buvid_fp=2d3824cddd83bcdd2bccd2ab350e24f0; buvid4=908281D4-96FA-7F63-D620-E400768E402402756-025071320-RlUK5omCFkl1gCy%2F0x1dhQ%3D%3D; b_lsid=7883D9C4_1980E6AA99D; rpdid=|(umRkmJ||Y)0J'u~lkl))lk); SESSDATA=bf8e6ae7%2C1768140567%2Cd8df8%2A72CjCHEsbeaFlRtyJutktKAOFmNLXhZrElVkhRMQbsR66pQtRYRz6HiUivsplVdMo5LFgSVi1LbV9RaWJIMmp5QXFfS0Z1WnhhX2EwTUJnbWM2Mm9wYVl0RGhfMURzUkgzdnJ4cUpRTE5zOEhtc0RnaU9rbEE5angyWVFqQW1BbmtRZnA4TEFRaDlBIIEC; bili_jct=bf80ba92326796097dd60d8c54ea1cf3; DedeUserID=3493088808930053; DedeUserID__ckMd5=2c8ca43685739904; theme-tip-show=SHOWED; theme-avatar-tip-show=SHOWED; bili_ticket=eyJhbGciOiJIUzI1NiIsImtpZCI6InMwMyIsInR5cCI6IkpXVCJ9.eyJleHAiOjE3NTI4NDc3NzMsImlhdCI6MTc1MjU4ODUxMywicGx0IjotMX0.z81d8OebdTtlNJUWpTcwON5JhcAlrz24a4kzP7W9rss; bili_ticket_expires=1752847713; sid=5e1augq9; CURRENT_FNVAL=2000; bp_t_offset_3493088808930053=1089865822718918656";
        return StringUtils.substringBetween(biliCookies, "bili_jct=", ";");
    }

    private String fetchSessData() {
//        String biliCookies = ConfigFetcher.getInitConfig().getBiliCookies();
        String biliCookies = "buvid3=61A174A7-52DA-7505-D1A4-7AE52E8508A102096infoc; b_nut=1752410402; _uuid=8BA81010BE-4167-1DC5-5A87-56B49AF108B3302553infoc; buvid_fp=2d3824cddd83bcdd2bccd2ab350e24f0; buvid4=908281D4-96FA-7F63-D620-E400768E402402756-025071320-RlUK5omCFkl1gCy%2F0x1dhQ%3D%3D; b_lsid=7883D9C4_1980E6AA99D; rpdid=|(umRkmJ||Y)0J'u~lkl))lk); SESSDATA=bf8e6ae7%2C1768140567%2Cd8df8%2A72CjCHEsbeaFlRtyJutktKAOFmNLXhZrElVkhRMQbsR66pQtRYRz6HiUivsplVdMo5LFgSVi1LbV9RaWJIMmp5QXFfS0Z1WnhhX2EwTUJnbWM2Mm9wYVl0RGhfMURzUkgzdnJ4cUpRTE5zOEhtc0RnaU9rbEE5angyWVFqQW1BbmtRZnA4TEFRaDlBIIEC; bili_jct=bf80ba92326796097dd60d8c54ea1cf3; DedeUserID=3493088808930053; DedeUserID__ckMd5=2c8ca43685739904; theme-tip-show=SHOWED; theme-avatar-tip-show=SHOWED; bili_ticket=eyJhbGciOiJIUzI1NiIsImtpZCI6InMwMyIsInR5cCI6IkpXVCJ9.eyJleHAiOjE3NTI4NDc3NzMsImlhdCI6MTc1MjU4ODUxMywicGx0IjotMX0.z81d8OebdTtlNJUWpTcwON5JhcAlrz24a4kzP7W9rss; bili_ticket_expires=1752847713; sid=5e1augq9; CURRENT_FNVAL=2000; bp_t_offset_3493088808930053=1089865822718918656";

        return StringUtils.substringBetween(biliCookies, "SESSDATA=", ";");
    }


    private JSONObject buildPostWorkParamOnWeb(List<RemoteSeverVideo> remoteSeverVideos, String recordPath) {
        File metaFile = new File(recordPath, UploaderFactory.getMetaFileName(getType()));
        BiliWorkMetaData workMetaData = FileStoreUtil.loadFromFile(metaFile, new TypeReference<BiliWorkMetaData>() {
        });

        List<JSONObject> videoObjs = Lists.newArrayList();
        for (RemoteSeverVideo remoteSeverVideo : remoteSeverVideos) {
            JSONObject videoObj = new JSONObject();
            videoObj.put("title", FileUtil.getPrefix(remoteSeverVideo.getLocalFilePath()));
            videoObj.put("filename", remoteSeverVideo.getServerFileName());
            videoObjs.add(videoObj);
        }

        String thumbnailUrl = uploadThumbnail(recordPath);

        JSONObject params = new JSONObject();
        params.put("cover", StringUtils.isBlank(thumbnailUrl) ? workMetaData.getCover() : thumbnailUrl);
        params.put("title", workMetaData.getTitle());
        params.put("tid", workMetaData.getTid());
        params.put("tag", StringUtils.join(workMetaData.getTags(), ","));
        params.put("desc", workMetaData.getDesc());
        params.put("dynamic", workMetaData.getDynamic());
        params.put("copyright", 1);
        params.put("source", workMetaData.getSource());
        params.put("videos", videoObjs);
        params.put("no_reprint", 0);
        params.put("open_elec", 1);
        params.put("csrf", fetchCsrf());

        return params;
    }

    /**
     * 上传视频封面
     *
     * @param recordPath
     * @return
     */
    private String uploadThumbnail(String recordPath) {
        File file = new File(recordPath, RecordConstant.THUMBNAIL_FILE_NAME);
        if (!file.exists()) {
            return null;
        }
        String base64Content = PictureFileUtil.fileToBase64(file);
        if (StringUtils.isBlank(base64Content)) {
            return null;
        }

        String csrf = fetchCsrf();
        String sessData = fetchSessData();
        RequestBody requestBody = new FormBody.Builder()
                .add("csrf", csrf)
                .add("cover", "data:image/jpeg;base64," + base64Content)
                .build();

        Request request = new Request.Builder()
                .url("https://member.bilibili.com/x/vu/web/cover/up")
                .post(requestBody)
                .addHeader("cookie", "SESSDATA=" + sessData + "; bili_jct=" + csrf)
                .build();
        String resp = OkHttpClientUtil.execute(request);
        JSONObject respObj = JSONObject.parseObject(resp);
        return respObj.getJSONObject("data").getString("url");
    }
}
