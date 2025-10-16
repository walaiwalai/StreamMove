package com.sh.engine.model.bili;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.URLUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.Lists;
import com.sh.config.exception.ErrorEnum;
import com.sh.config.exception.StreamerRecordException;
import com.sh.config.manager.ConfigFetcher;
import com.sh.config.model.config.StreamerConfig;
import com.sh.config.utils.*;
import com.sh.engine.constant.RecordConstant;
import com.sh.engine.model.StreamerInfoHolder;
import com.sh.engine.model.video.RemoteSeverVideo;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * B站单个视频上传命令
 */
@Slf4j
public class BiliWebVideoUploadCommand {
    public static final int CHUNK_RETRY = 10;
    public static final int CHUNK_RETRY_DELAY = 500;

    private File videoFile;

    private BiliWebPreUploadParams biliWebPreUploadParams;

    public BiliWebVideoUploadCommand(File videoFile) {
        this.videoFile = videoFile;

        this.biliWebPreUploadParams = fetchPreUploadInfo();
        if (biliWebPreUploadParams.getOk() == 1) {
            String upUrl = "https:" + biliWebPreUploadParams.getEndpoint()
                    + biliWebPreUploadParams.getUposUri().split("upos:/")[1];
            biliWebPreUploadParams.setUploadUrl(upUrl);
            String upId = fetchUploadId();
            biliWebPreUploadParams.setUploadId(upId);
        } else {
            throw new StreamerRecordException(ErrorEnum.PRE_UPLOAD_ERROR);
        }
    }

    /**
     * 获取上传视频
     * @return 上传的视频
     */
    public RemoteSeverVideo upload() throws Exception {
        if (this.biliWebPreUploadParams == null) {
            throw new StreamerRecordException(ErrorEnum.PRE_UPLOAD_ERROR);
        }
        long fileSize = FileUtil.size(this.videoFile);
        String videoName = this.videoFile.getName();
        int chunkSize = this.biliWebPreUploadParams.getChunkSize();
        int partCount = (int) Math.ceil(fileSize * 1.0 / this.biliWebPreUploadParams.getChunkSize());
        log.info("video size is {}M, seg {} parts to upload.", fileSize / 1024 / 1024, partCount);

        CountDownLatch countDownLatch = new CountDownLatch(partCount);
        List<Integer> failChunkNums = Lists.newCopyOnWriteArrayList();

//        File targetFile = EnvUtil.isStorageMounted() ? VideoFileUtil.copyMountedFileToLocal(videoFile) : videoFile;
        File targetFile = videoFile;
        String cookies = fetchBiliCookies();
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
                                .replace("{uploadUrl}", this.biliWebPreUploadParams.getUploadUrl())
                                .replace("{partNumber}", String.valueOf(finalI + 1))
                                .replace("{uploadId}", this.biliWebPreUploadParams.getUploadId())
                                .replace("{chunk}", String.valueOf(finalI))
                                .replace("{chunks}", String.valueOf(partCount))
                                .replace("{size}", String.valueOf(curChunkSize))
                                .replace("{start}", String.valueOf(curChunkStart))
                                .replace("{end}", String.valueOf(curChunkEnd))
                                .replace("{total}", String.valueOf(fileSize));
                        return uploadChunk(chunkUploadUrl, targetFile, finalI, partCount, (int) curChunkSize, curChunkStart, cookies);
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

        String finishUrl = String.format(RecordConstant.BILI_CHUNK_UPLOAD_FINISH_URL,
                this.biliWebPreUploadParams.getUploadUrl(),
                URLUtil.encode(videoName),
                this.biliWebPreUploadParams.getUploadId(),
                this.biliWebPreUploadParams.getBizId());
        boolean isFinish = finishChunks(finishUrl, completedPartNumbers);
        if (!isFinish) {
            return null;
        }

        // 4. 组装服务器端的视频
        String uposUri = this.biliWebPreUploadParams.getUposUri();
        String[] tmps = uposUri.split("//")[1].split("/");
        String fileNameOnServer = tmps[tmps.length - 1].split(".mp4")[0];

        // 5. 删除临时文件夹
//        if (EnvUtil.isStorageMounted()) {
//            FileUtils.deleteQuietly(targetFile.getParentFile());
//        }
        return new RemoteSeverVideo(fileNameOnServer, videoFile.getAbsolutePath());
    }

    /**
     * 上传分片
     *
     * @param uploadUrl
     * @param targetFile
     * @param chunkNo
     * @param totalChunks
     * @param curChunkSize
     * @param curChunkStart
     * @return
     */
    private boolean uploadChunk(String uploadUrl, File targetFile, Integer chunkNo, Integer totalChunks,
                                Integer curChunkSize, Long curChunkStart, String cookies) {
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
                .addHeader("cookie", cookies)
                .addHeader("x-upos-auth", this.biliWebPreUploadParams.getAuth())
                .put(chunkRequestBody)
                .build();

        String path = targetFile.getAbsolutePath();
        for (int i = 0; i < CHUNK_RETRY; i++) {
            try {
                if (i > 0) {
                    Thread.sleep(CHUNK_RETRY_DELAY);
                }
                String resp = OkHttpClientUtil.execute(request);
                System.out.println(resp);
                log.info("chunk upload success, file: {}, progress: {}/{}, time cost: {}s.", path, chunkShowNo, totalChunks,
                        (System.currentTimeMillis() - startTime) / 1000);
                return true;
            } catch (Exception e) {
                log.error("th {}th chunk upload error, retry: {}/{}", chunkShowNo, i + 1, CHUNK_RETRY, e);
            }
        }
        return false;
    }

    /**
     * 提交分片
     * @param finishUrl
     * @param completedPartNumbers
     * @return
     */
    private boolean finishChunks(String finishUrl, List<Integer> completedPartNumbers) {
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
                .addHeader("cookie", fetchBiliCookies())
                .addHeader("x-upos-auth", this.biliWebPreUploadParams.getAuth())
                .post(requestBody)
                .build();

        String resp = OkHttpClientUtil.execute(request);
        return Objects.equals(JSON.parseObject(resp).getString("OK"), "1");
    }

    private BiliWebPreUploadParams fetchPreUploadInfo() {
        String preUploadUrl = RecordConstant.BILI_WEB_PRE_UPLOAD_URL
                .replace("{name}", this.videoFile.getName())
                .replace("{size}", String.valueOf(FileUtil.size(this.videoFile)));
        Request request = new Request.Builder()
                .url(preUploadUrl)
                .addHeader("Accept", "*/*")
                .addHeader("Accept-Encoding", "deflate")
                .addHeader("Cookie", fetchBiliCookies())
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; WOW64) Chrome/63.0.3239.132 Safari/537.36")
                .build();
        String resp = OkHttpClientUtil.execute(request);
        return JSON.parseObject(resp, BiliWebPreUploadParams.class);
    }

    /**
     * 获取视频上传的id
     */
    private String fetchUploadId() {
        String url = this.biliWebPreUploadParams.getUploadUrl()
                + "?uploads&output=json&biz_id=" + this.biliWebPreUploadParams.getBizId()
                + "&filesize=" + FileUtil.size(this.videoFile)
                + "&profile=ugcfx%2Fbup&partsize=" + this.biliWebPreUploadParams.getChunkSize();
        Request request = new Request.Builder()
                .url(url)
                .addHeader("Accept", "*/*")
                .addHeader("Accept-Encoding", "deflate")
                .addHeader("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8,en-GB;q=0.7,en-US;q=0.6,ja;q=0.5")
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; WOW64) Chrome/63.0.3239.132 Safari/537.36")
                .addHeader("Origin", "https://member.bilibili.com")
                .addHeader("Referer", "https://member.bilibili.com/platform/upload/video/frame")
                .addHeader("X-Upos-Auth", this.biliWebPreUploadParams.getAuth())
                .post(RequestBody.create(MediaType.parse("application/json"), ""))
                .build();

        String resp = OkHttpClientUtil.execute(request);
        return JSON.parseObject(resp).getString("upload_id");
    }

    /**
     * 获取b站cookies
     * @return b站cookies
     */
    private String fetchBiliCookies() {
        String streamerName = StreamerInfoHolder.getCurStreamerName();
        StreamerConfig streamerConfig = ConfigFetcher.getStreamerInfoByName(streamerName);
        return StringUtils.isNotBlank(streamerConfig.getCertainBiliCookies()) ? streamerConfig.getCertainBiliCookies() : ConfigFetcher.getInitConfig().getBiliCookies();
    }
}
