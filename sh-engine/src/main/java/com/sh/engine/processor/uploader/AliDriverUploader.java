//package com.sh.engine.processor.uploader;
//
//import com.alibaba.fastjson.JSON;
//import com.google.common.collect.Lists;
//import com.sh.config.exception.ErrorEnum;
//import com.sh.config.exception.StreamerRecordException;
//import com.sh.config.manager.ConfigFetcher;
//import com.sh.config.model.video.RemoteSeverVideo;
//import com.sh.config.utils.HttpClientUtil;
//import com.sh.config.utils.VideoFileUtil;
//import com.sh.engine.base.StreamerInfoHolder;
//import com.sh.engine.constant.UploadPlatformEnum;
//import com.sh.engine.model.alidriver.*;
//import com.sh.message.service.MsgSendService;
//import lombok.extern.slf4j.Slf4j;
//import org.apache.commons.collections4.CollectionUtils;
//import org.apache.commons.io.FileUtils;
//import org.apache.commons.lang3.StringUtils;
//import org.apache.http.HttpEntity;
//import org.apache.http.entity.ByteArrayEntity;
//import org.springframework.stereotype.Component;
//
//import javax.annotation.Resource;
//import java.io.File;
//import java.io.IOException;
//import java.util.Collection;
//import java.util.List;
//import java.util.stream.Collectors;
//
///**
// * 阿里云盘上传器
// *
// * @Author : caiwen
// * @Date: 2024/9/30
// */
//@Slf4j
//@Component
//public class AliDriverUploader extends Uploader {
//    @Resource
//    private MsgSendService msgSendService;
//
//    private static AliStoreBucket STORE_BUCKET;
//    /**
//     * 分块上传大小为10M
//     */
//    private static final long UPLOAD_CHUNK_SIZE = 1024 * 1024 * 5;
//    private static final int RETRY_COUNT = 3;
//
//    /**
//     * 上传连接有效时间：1小时
//     */
//    private static final long UPLOAD_URL_VALID_TIME = 1000 * 3400;
//
//    @Override
//    public String getType() {
//        return UploadPlatformEnum.ALI_DRIVER.getType();
//    }
//
//    @Override
//    public void setUp() {
//        String refreshToken = ConfigFetcher.getInitConfig().getRefreshToken();
//        assert StringUtils.isNotBlank(refreshToken);
//
//        STORE_BUCKET = new AliStoreBucket(refreshToken);
//        assert StringUtils.isNotBlank(STORE_BUCKET.getAccessToken());
//
//        log.info("init aliDriver param success, driverId: {}, userId: {}", STORE_BUCKET.getDriveId(), STORE_BUCKET.getUserId());
//    }
//
//    @Override
//    public boolean upload(String recordPath) throws Exception {
//        String targetFileId = ConfigFetcher.getInitConfig().getTargetFileId();
//        Collection<File> files = FileUtils.listFiles(new File(recordPath), new String[]{"mp4"}, false);
//        if (CollectionUtils.isEmpty(files)) {
//            return true;
//        }
//        for (File targetFile : files) {
//            RemoteSeverVideo remoteSeverVideo = getUploadedVideo(targetFile);
//            if (remoteSeverVideo != null) {
//                log.info("video has been uploaded to ali driver, file: {}", targetFile.getAbsolutePath());
//                continue;
//            }
//
//            remoteSeverVideo = uploadFile(targetFileId, targetFile);
//            if (remoteSeverVideo != null) {
//                msgSendService.sendText(targetFile.getAbsolutePath() + "路径下的视频上传阿里云盘成功！");
//                saveUploadedVideo(remoteSeverVideo);
//            } else {
//                msgSendService.sendText(targetFile.getAbsolutePath() + "路径下的视频上传阿里云盘失败！");
//                throw new StreamerRecordException(ErrorEnum.POST_WORK_ERROR);
//            }
//        }
//
//        // 清理上传过的视频
//        clearUploadedVideos();
//
//        return true;
//    }
//
//    private RemoteSeverVideo uploadFile(String parentId, File targetFile) throws Exception {
//        long fileSize = targetFile.length();
//        String fileName = StreamerInfoHolder.getCurStreamerName() + "-" + targetFile.getParentFile().getName() + ".mp4";
//
//        // 1. 进行pre_hash
//        STORE_BUCKET.preHash(targetFile, fileName, parentId);
//
//        // 2.分块进行上传
//        return uploadByChunk(targetFile, fileSize);
//    }
//
//    /**
//     * 不能多线程上传，必须顺序上传，会报错
//     *
//     * @param localVideo
//     * @param fileSize
//     * @return
//     * @throws Exception
//     */
//    private RemoteSeverVideo uploadByChunk(File localVideo, long fileSize) throws Exception {
//        // 1. 获取上传信息
//        String targetFileId = ConfigFetcher.getInitConfig().getTargetFileId();
//        String fileName = StreamerInfoHolder.getCurStreamerName() + "-" + localVideo.getParentFile().getName() + ".mp4";
//        CreateFileResponse fileInfo = STORE_BUCKET.contentHash(localVideo, fileName, targetFileId);
//        if (fileInfo.isRapidUpload() || fileInfo.isExist()) {
//            // 极速上传或文件已经存在，跳过
//            log.info("rapid upload targetFile or targetFile existed, targetFile: {}", localVideo.getAbsolutePath());
//            return new RemoteSeverVideo("", localVideo.getAbsolutePath());
//        }
//
//        // 2. 分快上传
//        int partCount = (int) Math.ceil(fileSize * 1.0 / UPLOAD_CHUNK_SIZE);
//        log.info("video size is {}M, seg {} parts to upload.", fileSize / 1024 / 1024, partCount);
//        long uploadStartTime = System.currentTimeMillis();
//        List<Integer> failChunkNums = Lists.newCopyOnWriteArrayList();
//
//        for (int i = 0; i < partCount; i++) {
//            long curChunkStart = (long) i * UPLOAD_CHUNK_SIZE;
//            long curChunkSize = (i + 1 == partCount) ? (fileSize - curChunkStart) : UPLOAD_CHUNK_SIZE;
//            // 这个链接有效时间是一个小时
//            if (uploadStartTime + UPLOAD_URL_VALID_TIME < System.currentTimeMillis()) {
//                log.info("upload may expired, try to fresh upload info, lastValidTime: {}", uploadStartTime);
//                fileInfo = refreshFileInfo(fileInfo);
//                uploadStartTime = System.currentTimeMillis();
//            }
//
//            // 根据上传连接上传文件
//            String uploadUrl = fileInfo.getPartInfoList().get(i).getUploadUrl();
//            boolean uploadChunkSuccess = uploadChunk(uploadUrl, localVideo, i, partCount, (int) curChunkSize, curChunkStart);
//            if (!uploadChunkSuccess) {
//                failChunkNums.add(i);
//                break;
//            }
//        }
//
//        if (CollectionUtils.isEmpty(failChunkNums)) {
//            log.info("video chunks upload success, videoPath: {}", localVideo.getAbsolutePath());
//        } else {
//            log.error("video chunks upload fail, failed chunkNos: {}", JSON.toJSONString(failChunkNums));
//            return null;
//        }
//
//        // 2. 调用完成整个视频上传
//        AliFileDTO aliFile = STORE_BUCKET.complete(fileInfo);
//        if (aliFile == null || StringUtils.isBlank(aliFile.getFileId())) {
//            log.error("file upload to aliDriver failed, resp: {}", JSON.toJSONString(aliFile));
//            return null;
//        }
//
//        return new RemoteSeverVideo(aliFile.getFileId(), localVideo.getAbsolutePath());
//    }
//
//    private boolean uploadChunk(String uploadUrl, File targetFile, int index, Integer totalChunks, Integer curChunkSize, Long curChunkStart) {
//        int chunkShowNo = index + 1;
//        long startTime = System.currentTimeMillis();
//
//        byte[] bytes = null;
//        try {
//            bytes = VideoFileUtil.fetchBlock(targetFile, curChunkStart, curChunkSize);
//        } catch (IOException e) {
//            log.error("fetch chunk error", e);
//            return false;
//        }
//        HttpEntity requestEntity = new ByteArrayEntity(bytes);
//
//        for (int i = 0; i < RETRY_COUNT; i++) {
//            try {
//                String resp = HttpClientUtil.sendPut(uploadUrl, null, requestEntity, false);
//                if (StringUtils.isBlank(resp)) {
//                    log.info("{} chunk upload success, progress: {}/{}, time cost: {}s.", getType(), chunkShowNo,
//                            totalChunks, (System.currentTimeMillis() - startTime) / 1000);
//                    return true;
//                } else {
//                    log.error("{}th chunk upload failed, retry: {}/{}, resp: {}", chunkShowNo, i + 1, RETRY_COUNT, resp);
//                }
//            } catch (Exception e) {
//                log.error("{}th chunk upload error, retry: {}/{}", chunkShowNo, i + 1, RETRY_COUNT, e);
//            }
//        }
//        return false;
//    }
//
//    private CreateFileResponse refreshFileInfo(CreateFileResponse old) {
//        GetUploadUrlRequest uploadUrlRequest = new GetUploadUrlRequest();
//        uploadUrlRequest.setDriverId(old.getDriveId());
//        uploadUrlRequest.setFileId(old.getFileId());
//        uploadUrlRequest.setUploadId(old.getUploadId());
//        uploadUrlRequest.setPartInfoList(old.getPartInfoList().stream()
//                .map(p -> new UploadPartInfo(p.getPartNumber()))
//                .collect(Collectors.toList()));
//
//        return STORE_BUCKET.getUploadUrl(uploadUrlRequest);
//    }
//}
