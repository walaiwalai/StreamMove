package com.sh.engine.upload;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.sh.config.exception.ErrorEnum;
import com.sh.config.exception.StreamerRecordException;
import com.sh.config.manager.ConfigFetcher;
import com.sh.config.model.config.StreamerConfig;
import com.sh.config.model.stauts.FileStatusModel;
import com.sh.config.model.video.FailUploadVideoChunk;
import com.sh.config.model.video.LocalVideo;
import com.sh.config.model.video.RemoteSeverVideo;
import com.sh.config.model.video.UploadVideoPair;
import com.sh.config.utils.HttpClientUtil;
import com.sh.config.utils.VideoFileUtils;
import com.sh.engine.UploadPlatformEnum;
import com.sh.engine.base.StreamerInfoHolder;
import com.sh.engine.model.bili.BiliWebPreUploadCommand;
import com.sh.engine.model.bili.web.BiliClientPreUploadParams;
import com.sh.engine.model.bili.web.VideoUploadResultModel;
import com.sh.engine.model.upload.BaseUploadTask;
import com.sh.engine.service.MsgSendService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.mime.FormBodyPartBuilder;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.entity.mime.content.ByteArrayBody;
import org.apache.http.entity.mime.content.StringBody;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;

/**
 * @Author caiwen
 * @Date 2024 03 10 12 23
 **/
@Component
@Slf4j
public class BiliClientWorkUploadService extends AbstractWorkUploadService {
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
    private static final Map<String, String> CLIENT_HEADERS = Maps.newHashMap();

    static {
        CLIENT_HEADERS.put("Connection", "keep-alive");
        CLIENT_HEADERS.put("Content-Type", "application/json");
        CLIENT_HEADERS.put("user-agent", "");
    }


    @Autowired
    private MsgSendService msgSendService;

    @Override
    public String getName() {
        return UploadPlatformEnum.BILI_CLIENT.getType();
    }

    @Override
    public boolean upload(List<LocalVideo> localVideos, BaseUploadTask task) throws Exception {
        String dirName = task.getDirName();

        // 1. 多个视频分P进行上传
        FileStatusModel fileStatus = FileStatusModel.loadFromFile(dirName);
        UploadVideoPair videoParts = fileStatus.fetchVideoPartByPlatform(getName());
        List<RemoteSeverVideo> remoteVideos = Optional.ofNullable(videoParts)
                .map(UploadVideoPair::getSucceedUploadedVideos)
                .orElse(Lists.newArrayList())
                .stream()
                .map(succeedUploadSeverVideo -> (RemoteSeverVideo) succeedUploadSeverVideo)
                .collect(Collectors.toList());

        for (int i = 0; i < localVideos.size(); i++) {
            LocalVideo localVideo = localVideos.get(i);
            if (localVideo.isUpload()) {
                continue;
            }

            // 1.1 上传单个视频
            VideoUploadResultModel biliVideoUploadResult = uploadOnClient(localVideo);
            if (CollectionUtils.isNotEmpty(biliVideoUploadResult.getFailedChunks()) || !biliVideoUploadResult
                    .isComplete() || biliVideoUploadResult.getRemoteSeverVideo() == null) {
                // 上传chunks失败，完成上传失败，发送作品为空均视为失败
                localVideo.setUpload(false);
                syncStatus(dirName, localVideo, biliVideoUploadResult);
                throw new StreamerRecordException(ErrorEnum.UPLOAD_CHUNK_ERROR);
            } else {
                localVideo.setUpload(true);
                syncStatus(dirName, localVideo, biliVideoUploadResult);
            }
            remoteVideos.add(biliVideoUploadResult.getRemoteSeverVideo());

            // 1.2 发消息
            msgSendService.send(localVideo.getLocalFileFullPath() + "路径下的视频上传B站成功！");
        }

        // 2. 给需要上传的视频文件命名
        for (int i = 0; i < remoteVideos.size(); i++) {
            remoteVideos.get(i).setTitle("P" + (i + 1));
        }

        boolean isPostSuccess = postWork(remoteVideos, task);
        if (!isPostSuccess) {
            throw new StreamerRecordException(ErrorEnum.POST_WORK_ERROR);
        }

        return true;
    }

    private VideoUploadResultModel uploadOnClient(LocalVideo localVideo) throws Exception {
        File videoFile = new File(localVideo.getLocalFileFullPath());
        BiliWebPreUploadCommand command = new BiliWebPreUploadCommand(videoFile);
        command.doClientPreUp();

        BiliClientPreUploadParams preUploadData = command.getBiliClientPreUploadParams();
        String uploadUrl = preUploadData.getUrl();
        String completeUrl = preUploadData.getComplete();
        String serverFileName = preUploadData.getFilename();

        String videoName = videoFile.getName();
        long fileSize = localVideo.getFileSize();
        VideoUploadResultModel uploadResult = new VideoUploadResultModel();

        // 1.进行视频分块上传
        int partCount = (int) Math.ceil(fileSize * 1.0 / CHUNK_SIZE);
        log.info("video size is {}M, seg {} parts to upload.", fileSize / 1024 / 1024, partCount);
        CountDownLatch countDownLatch = new CountDownLatch(partCount);
        List<FailUploadVideoChunk> failUploadVideoChunks = Lists.newCopyOnWriteArrayList();
        for (int i = 0; i < partCount; i++) {
            //当前分段起始位置
            long curChunkStart = (long) i * CHUNK_SIZE;
            // 当前分段大小  如果为最后一个大小为fileSize-curChunkStart  其他为partSize
            long curChunkSize = (i + 1 == partCount) ? (fileSize - curChunkStart) : CHUNK_SIZE;

            int finalI = i;
            CompletableFuture.supplyAsync(() -> {
                        return uploadChunk(uploadUrl, videoFile, finalI, partCount,
                                (int) curChunkSize, curChunkStart);
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
        countDownLatch.await();

        if (CollectionUtils.isEmpty(failUploadVideoChunks)) {
            log.info("video chunks upload success, videoPath: {}", localVideo.getLocalFileFullPath());
        } else {
            log.error("video chunks upload fail, failed chunkNos: {}", failUploadVideoChunks.stream().map(
                    FailUploadVideoChunk::getChunkNo).collect(Collectors.toList()));
            uploadResult.setFailedChunks(failUploadVideoChunks);
            return uploadResult;
        }

        // 2. 调用完成整个视频上传
        boolean isComplete = finishChunks(completeUrl, partCount, videoName, localVideo);
        uploadResult.setComplete(isComplete);
        if (!isComplete) {
            return uploadResult;
        }

        RemoteSeverVideo remoteSeverVideo = new RemoteSeverVideo(localVideo.getTitle(), serverFileName);
        uploadResult.setRemoteSeverVideo(remoteSeverVideo);

        return uploadResult;
    }

    private boolean uploadChunk(String uploadUrl, File targetFile, Integer chunkNo, Integer totalChunks, Integer curChunkSize, Long curChunkStart) {
        int chunkShowNo = chunkNo + 1;
        long startTime = System.currentTimeMillis();

        byte[] bytes = null;
        try {
            bytes = VideoFileUtils.fetchBlock(targetFile, curChunkStart, curChunkSize);
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


    private boolean postWork(List<RemoteSeverVideo> remoteSeverVideos, BaseUploadTask task) {
        if (CollectionUtils.isEmpty(remoteSeverVideos)) {
            return false;
        }

        StreamerConfig streamerConfig = ConfigFetcher.getStreamerInfoByName(StreamerInfoHolder.getCurStreamerName());
        String accessToken = ConfigFetcher.getInitConfig().getAccessToken();
        String postWorkUrl = String.format(CLIENT_POST_VIDEO_URL, accessToken);
        for (int i = 0; i < RETRY_COUNT; i++) {
            try {
                Thread.sleep(CHUNK_RETRY_DELAY);
                String resp = HttpClientUtil.sendPost(postWorkUrl, CLIENT_HEADERS,
                        buildPostWorkParamOnClient(streamerConfig, remoteSeverVideos, task));
                JSONObject respObj = JSONObject.parseObject(resp);
                if (Objects.equals(respObj.getString("code"), "0")) {
                    log.info("postWork success, video is uploaded, remoteSeverVideos: {}",
                            JSON.toJSONString(remoteSeverVideos));
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

    private JSONObject buildPostWorkParamOnClient(StreamerConfig streamerConfig, List<RemoteSeverVideo> remoteSeverVideos, BaseUploadTask task) {
        JSONObject params = new JSONObject();
        params.put("cover", streamerConfig.getCover());
        params.put("build", 1088);
        params.put("title", task.getTitle());
        params.put("tid", streamerConfig.getTid());
        params.put("tag", StringUtils.join(streamerConfig.getTags(), ","));
        params.put("desc", streamerConfig.getDesc());
        params.put("dynamic", streamerConfig.getDynamic());
        params.put("copyright", 1);
        params.put("source", streamerConfig.getSource());
        params.put("videos", remoteSeverVideos);
        params.put("no_reprint", 0);
        params.put("open_elec", 1);

        return params;
    }
}
