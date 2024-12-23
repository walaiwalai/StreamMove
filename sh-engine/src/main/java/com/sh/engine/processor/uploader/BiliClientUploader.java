package com.sh.engine.processor.uploader;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.file.FileNameUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.TypeReference;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.sh.config.exception.ErrorEnum;
import com.sh.config.exception.StreamerRecordException;
import com.sh.config.manager.CacheManager;
import com.sh.config.manager.ConfigFetcher;
import com.sh.config.model.video.LocalVideo;
import com.sh.config.model.video.RemoteSeverVideo;
import com.sh.config.utils.ExecutorPoolUtil;
import com.sh.config.utils.FileStoreUtil;
import com.sh.config.utils.HttpClientUtil;
import com.sh.config.utils.VideoFileUtil;
import com.sh.engine.constant.UploadPlatformEnum;
import com.sh.engine.model.bili.BiliWebPreUploadCommand;
import com.sh.engine.model.bili.web.BiliClientPreUploadParams;
import com.sh.engine.processor.uploader.meta.BiliClientWorkMetaData;
import com.sh.message.service.MsgSendService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.FileFilterUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.mime.FormBodyPartBuilder;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.entity.mime.content.ByteArrayBody;
import org.apache.http.entity.mime.content.StringBody;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;

/**
 * @Author caiwen
 * @Date 2024 09 28 22 49
 **/
@Slf4j
@Component
public class BiliClientUploader extends Uploader {
    @Resource
    private CacheManager cacheManager;
    @Resource
    private MsgSendService msgSendService;

    /**
     * 视频上传分块大小为5M
     */
    private static final int CHUNK_SIZE = 1024 * 1024 * 5;
    /**
     * 失败重试次数
     */
    public static final Integer RETRY_COUNT = 10;
    public static final Integer CHUNK_RETRY_DELAY = 500;

    private static final String CLIENT_POST_VIDEO_URL
            = "https://member.bilibili.com/x/vu/client/add?access_key=%s";
    private static final String SUCCESS_UPLOAD_VIDEO_KEY = "bili_client_upload_succeed";
    private static final Map<String, String> CLIENT_HEADERS = Maps.newHashMap();

    static {
        CLIENT_HEADERS.put("Connection", "keep-alive");
        CLIENT_HEADERS.put("Content-Type", "application/json");
        CLIENT_HEADERS.put("user-agent", "");
    }

    @Override
    public String getType() {
        return UploadPlatformEnum.BILI_CLIENT.getType();
    }

    @Override
    public void setUp() {

    }

    @Override
    public boolean upload(String recordPath) throws Exception {
        // 0. 获取要上传的文件
        List<LocalVideo> localVideos = fetchLocalVideos(recordPath);
        if (CollectionUtils.isEmpty(localVideos)) {
            return true;
        }

        // 1. 上传视频
        List<RemoteSeverVideo> remoteVideos = Lists.newArrayList();
        for (int i = 0; i < localVideos.size(); i++) {
            LocalVideo localVideo = localVideos.get(i);
            String remoteFileName = cacheManager.getHash(SUCCESS_UPLOAD_VIDEO_KEY, localVideo.getLocalFileFullPath(), new TypeReference<String>() {
            });
            if (StringUtils.isNotBlank(remoteFileName)) {
                remoteVideos.add(new RemoteSeverVideo("", remoteFileName));
                continue;
            }

            // 1.1 上传单个视频
            RemoteSeverVideo remoteVideo = uploadOnClient(localVideo);
            if (remoteVideo == null) {
                // 上传失败，发送作品为空均视为失败
                msgSendService.sendText(localVideo.getLocalFileFullPath() + "路径下的视频上传B站失败！");
                throw new StreamerRecordException(ErrorEnum.UPLOAD_CHUNK_ERROR);
            }

            cacheManager.setHash(SUCCESS_UPLOAD_VIDEO_KEY, localVideo.getLocalFileFullPath(), remoteVideo.getFilename());
            remoteVideos.add(remoteVideo);

            // 1.2 发消息
            msgSendService.sendText(localVideo.getLocalFileFullPath() + "路径下的视频上传B站成功！");
        }

        // 2. 给需要上传的视频文件命名
        for (int i = 0; i < remoteVideos.size(); i++) {
            remoteVideos.get(i).setTitle("P" + (i + 1));
        }

        // 3. 提交作品
        boolean isPostSuccess = postWork(remoteVideos, recordPath);
        if (!isPostSuccess) {
            throw new StreamerRecordException(ErrorEnum.POST_WORK_ERROR);
        }

        // 4. 提交成功清理一下缓存
        for (LocalVideo localVideo : localVideos) {
            cacheManager.deleteHashTag(SUCCESS_UPLOAD_VIDEO_KEY, localVideo.getLocalFileFullPath());
        }

        return true;
    }

    /**
     * 获取要上传的视频
     *
     * @param dirName 文件地址
     * @return 要上传的视频
     */
    private List<LocalVideo> fetchLocalVideos(String dirName) {
        long videoPartLimitSize = ConfigFetcher.getInitConfig().getVideoPartLimitSize() * 1024L * 1024L;

        // 遍历本地的视频文件
        List<LocalVideo> localVideos = FileUtils.listFiles(new File(dirName), FileFilterUtils.suffixFileFilter("mp4"), null)
                .stream()
                .sorted(Comparator.comparingLong(File::lastModified))
                .map(file -> {
                    if (FileUtil.size(file) < videoPartLimitSize) {
                        return null;
                    }
                    return LocalVideo.builder()
                            .localFileFullPath(file.getAbsolutePath())
                            .title(FileNameUtil.getPrefix(file))
                            .fileSize(FileUtil.size(file))
                            .build();
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        log.info("Final videoParts: {}", JSON.toJSONString(localVideos));
        return localVideos;
    }

    private RemoteSeverVideo uploadOnClient(LocalVideo localVideo) throws Exception {
        File videoFile = new File(localVideo.getLocalFileFullPath());
        BiliWebPreUploadCommand command = new BiliWebPreUploadCommand(videoFile);
        command.doClientPreUp();

        BiliClientPreUploadParams preUploadData = command.getBiliClientPreUploadParams();
        String uploadUrl = preUploadData.getUrl();
        String completeUrl = preUploadData.getComplete();
        String serverFileName = preUploadData.getFilename();

        String videoName = videoFile.getName();
        long fileSize = localVideo.getFileSize();

        // 1.进行视频分块上传
        int partCount = (int) Math.ceil(fileSize * 1.0 / CHUNK_SIZE);
        log.info("video size is {}M, seg {} parts to upload.", fileSize / 1024 / 1024, partCount);
        CountDownLatch countDownLatch = new CountDownLatch(partCount);
        List<Integer> failChunkNums = Lists.newCopyOnWriteArrayList();
        for (int i = 0; i < partCount; i++) {
            //当前分段起始位置
            long curChunkStart = (long) i * CHUNK_SIZE;
            // 当前分段大小  如果为最后一个大小为fileSize-curChunkStart  其他为partSize
            long curChunkSize = (i + 1 == partCount) ? (fileSize - curChunkStart) : CHUNK_SIZE;

            int finalI = i;
            CompletableFuture.supplyAsync(() -> {
                        return uploadChunk(uploadUrl, videoFile, finalI, partCount,
                                (int) curChunkSize, curChunkStart);
                    }, ExecutorPoolUtil.getUploadPool())
                    .whenComplete((isSuccess, throwbale) -> {
                        if (!isSuccess) {
                            failChunkNums.add(finalI);
                        }
                        countDownLatch.countDown();
                    });
        }
        countDownLatch.await();

        if (CollectionUtils.isEmpty(failChunkNums)) {
            log.info("video chunks upload success, videoPath: {}", localVideo.getLocalFileFullPath());
        } else {
            log.error("video chunks upload fail, failed chunkNos: {}", JSON.toJSONString(failChunkNums));
            return null;
        }

        // 2. 调用完成整个视频上传
        boolean isComplete = finishChunks(completeUrl, partCount, videoName, localVideo);
        if (!isComplete) {
            return null;
        }

        return new RemoteSeverVideo(localVideo.getTitle(), serverFileName);
    }

    private boolean uploadChunk(String uploadUrl, File targetFile, Integer chunkNo, Integer totalChunks, Integer curChunkSize, Long curChunkStart) {
        int chunkShowNo = chunkNo + 1;
        long startTime = System.currentTimeMillis();

        byte[] bytes = null;
        try {
            bytes = VideoFileUtil.fetchBlock(targetFile, curChunkStart, curChunkSize);
        } catch (IOException e) {
            log.error("fetch chunk error", e);
            return false;
        }
        String md5Str = DigestUtils.md5Hex(bytes);
        HttpEntity requestEntity = MultipartEntityBuilder.create()
                .addPart(FormBodyPartBuilder.create()
                        .setName("version")
                        .setBody(new StringBody("2.3.0.1088", ContentType.APPLICATION_FORM_URLENCODED))
                        .build())
                .addPart(FormBodyPartBuilder.create()
                        .setName("filesize")
                        .setBody(new StringBody(String.valueOf(curChunkSize), ContentType.APPLICATION_FORM_URLENCODED))
                        .build())
                .addPart(FormBodyPartBuilder.create()
                        .setName("chunk")
                        .setBody(new StringBody(String.valueOf(chunkNo), ContentType.APPLICATION_FORM_URLENCODED))
                        .build())
                .addPart(FormBodyPartBuilder.create()
                        .setName("chunks")
                        .setBody(new StringBody(String.valueOf(totalChunks), ContentType.APPLICATION_FORM_URLENCODED))
                        .build())
                .addPart(FormBodyPartBuilder.create()
                        .setName("md5")
                        .setBody(new StringBody(md5Str, ContentType.APPLICATION_FORM_URLENCODED))
                        .build())
                .addPart(FormBodyPartBuilder.create()
                        .setName("file")
                        .setBody(new ByteArrayBody(bytes, ContentType.APPLICATION_OCTET_STREAM, targetFile.getName()))
                        .build())
                .build();

        String path = targetFile.getAbsolutePath();
        for (int i = 0; i < RETRY_COUNT; i++) {
            try {
                Thread.sleep(CHUNK_RETRY_DELAY);
                String respStr = HttpClientUtil.sendPost(uploadUrl, null, requestEntity, false);
                JSONObject respObj = JSONObject.parseObject(respStr);
                if (Objects.equals(respObj.getString("info"), "Successful.")) {
                    log.info("chunk upload success, file: {}, progress: {}/{}, time cost: {}s.", path, chunkShowNo, totalChunks,
                            (System.currentTimeMillis() - startTime) / 1000);
                    return true;
                } else {
                    log.error("{}th chunk upload fail, file: {}, ret: {}, retry: {}/{}", chunkShowNo, targetFile.getAbsoluteFile(), respStr, i + 1, RETRY_COUNT);
                }
            } catch (Exception e) {
                log.error("{}th chunk upload error, file: {}, retry: {}/{}", chunkShowNo, path, i + 1, RETRY_COUNT, e);
            }
        }
        return false;
    }


    public boolean finishChunks(String finishUrl, int totalChunks, String videoName, LocalVideo localVideo) throws Exception {
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

        for (int i = 0; i < RETRY_COUNT; i++) {
            try {
                Thread.sleep(CHUNK_RETRY_DELAY);
                String completeResp = HttpClientUtil.sendPost(finishUrl, null, completeEntity);
                JSONObject respObj = JSONObject.parseObject(completeResp);
                if (Objects.equals(respObj.getString("OK"), "1")) {
                    log.info("complete upload success, videoName: {}", videoName);
                    return true;
                } else {
                    log.error("complete upload fail, videoName: {}, retry: {}/{}.", videoName, i + 1, RETRY_COUNT);
                }
            } catch (Exception e) {
                log.error("complete upload error, retry: {}/{}", i + 1, RETRY_COUNT, e);
            }
        }
        return false;
    }


    private boolean postWork(List<RemoteSeverVideo> remoteSeverVideos, String recordPath) {
        if (CollectionUtils.isEmpty(remoteSeverVideos)) {
            return false;
        }

        String accessToken = ConfigFetcher.getInitConfig().getAccessToken();
        String postWorkUrl = String.format(CLIENT_POST_VIDEO_URL, accessToken);
        for (int i = 0; i < RETRY_COUNT; i++) {
            try {
                Thread.sleep(CHUNK_RETRY_DELAY);
                String resp = HttpClientUtil.sendPost(postWorkUrl, CLIENT_HEADERS,
                        buildPostWorkParamOnClient(remoteSeverVideos, recordPath));
                JSONObject respObj = JSONObject.parseObject(resp);
                if (Objects.equals(respObj.getString("code"), "0")) {
                    log.info("postWork success, video is uploaded, recordPath: {}", recordPath);
                    return true;
                } else {
                    log.error("postWork failed, res: {}, title: {}, retry: {}/{}.", resp, JSON.toJSONString(remoteSeverVideos), i + 1, RETRY_COUNT);
                }
            } catch (Exception e) {
                log.error("postWork error, retry: {}/{}", i + 1, RETRY_COUNT, e);
            }
        }
        return false;
    }

    private JSONObject buildPostWorkParamOnClient(List<RemoteSeverVideo> remoteSeverVideos, String recordPath) {
        File metaFile = new File(recordPath, UploaderFactory.getMetaFileName(getType()));
        BiliClientWorkMetaData workMetaData = FileStoreUtil.loadFromFile(metaFile, new TypeReference<BiliClientWorkMetaData>() {
        });

        JSONObject params = new JSONObject();
        params.put("cover", workMetaData.getCover());
        params.put("build", 1088);
        params.put("title", workMetaData.getTitle());
        params.put("tid", workMetaData.getTid());
        params.put("tag", StringUtils.join(workMetaData.getTags(), ","));
        params.put("desc", workMetaData.getDesc());
        params.put("dynamic", workMetaData.getDynamic());
        params.put("copyright", 1);
        params.put("source", workMetaData.getSource());
        params.put("videos", remoteSeverVideos);
        params.put("no_reprint", 0);
        params.put("open_elec", 1);

        return params;
    }
}
