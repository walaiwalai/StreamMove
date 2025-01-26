package com.sh.engine.processor.uploader;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.sh.config.exception.ErrorEnum;
import com.sh.config.exception.StreamerRecordException;
import com.sh.config.manager.ConfigFetcher;
import com.sh.config.model.video.RemoteSeverVideo;
import com.sh.config.utils.HttpClientUtil;
import com.sh.config.utils.VideoFileUtil;
import com.sh.engine.base.StreamerInfoHolder;
import com.sh.engine.constant.UploadPlatformEnum;
import com.sh.engine.model.alidriver.*;
import com.sh.engine.util.AliDriverUtil;
import com.sh.message.service.MsgSendService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpEntity;
import org.apache.http.entity.ByteArrayEntity;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 阿里云盘上传器
 * @Author : caiwen
 * @Date: 2024/9/30
 */
@Slf4j
@Component
public class AliDriverUploader extends Uploader {
    @Resource
    private MsgSendService msgSendService;

    private static AliStoreBucket STORE_BUCKET;
    /**
     * 分块上传大小为10M
     */
    private static final long UPLOAD_CHUNK_SIZE = 1024 * 1024 * 5;
    private static final String FILE_CREATE_WITH_FOLDERS_URL = "https://api.aliyundrive.com/adrive/v2/file/createWithFolders";
    private static final String FILE_GET_UPLOAD_URL = "https://api.aliyundrive.com/v2/file/get_upload_url";
    private static final String FILE_COMPLETE_URL = "https://api.aliyundrive.com/v2/file/complete";
    private static final int RETRY_COUNT = 3;
    private static final int CHUNK_RETRY_DELAY = 500;

    @Override
    public String getType() {
        return UploadPlatformEnum.ALI_DRIVER.getType();
    }

    @Override
    public void setUp() {
        String refreshToken = ConfigFetcher.getInitConfig().getRefreshToken();
        assert StringUtils.isNotBlank(refreshToken);

        STORE_BUCKET = new AliStoreBucket(refreshToken);
        STORE_BUCKET.refreshToken();
        assert StringUtils.isNotBlank(STORE_BUCKET.getAccessToken());

        log.info("init aliDriver param success, dirverId: {}, userId: {}", STORE_BUCKET.getDriveId(), STORE_BUCKET.getUserId());
    }

    @Override
    public boolean upload(String recordPath) throws Exception {
        String targetFileId = ConfigFetcher.getInitConfig().getTargetFileId();
        Collection<File> files = FileUtils.listFiles(new File(recordPath), new String[]{"mp4"}, false);
        if (CollectionUtils.isEmpty(files)) {
            return true;
        }
        for (File targetFile : files) {
            RemoteSeverVideo remoteSeverVideo = getUploadedVideo(targetFile);
            if (remoteSeverVideo != null) {
                log.info("video has been uploaded to ali driver, file: {}", targetFile.getAbsolutePath());
                continue;
            }

            remoteSeverVideo = uploadFile(targetFileId, targetFile);
            if (remoteSeverVideo != null) {
                msgSendService.sendText(targetFile.getAbsolutePath() + "路径下的视频上传阿里云盘成功！");
                saveUploadedVideo(remoteSeverVideo);
            } else {
                msgSendService.sendText(targetFile.getAbsolutePath() + "路径下的视频上传阿里云盘失败！");
                throw new StreamerRecordException(ErrorEnum.POST_WORK_ERROR);
            }
        }

        // 清理上传过的视频
        clearUploadedVideos();

        return true;
    }

    private RemoteSeverVideo uploadFile(String parentId, File targetFile) throws Exception {
        long fileSize = targetFile.length();
        String fileName = StreamerInfoHolder.getCurStreamerName() + "-" + targetFile.getParentFile().getName() + ".mp4";

        // 1. 进行pre_hash
        preHash(targetFile, fileName, parentId);

        // 2. 进行content_hash
        CreateFileResponse response = contentHash(targetFile, fileName, parentId).toJavaObject(CreateFileResponse.class);
        if (response.isRapidUpload() || response.isExist()) {
            // 极速上传或文件已经存在，跳过
            log.info("rapid upload targetFile or targetFile existed, targetFile: {}", targetFile.getAbsolutePath());
            return new RemoteSeverVideo("", targetFile.getAbsolutePath());
        }

        // 3.分块进行上传
        return uploadByChunk(targetFile, fileSize, response);
    }

    private JSONObject preHash(File uploadFile, String fileName, String targetParentFileId) throws Exception {
        byte[] buff = VideoFileUtil.fetchBlock(uploadFile, 0, 1024);
        String preHash = AliDriverUtil.sha1(buff, 0, 1024);

        long size = uploadFile.length();
        CreateFileRequest createFileRequest = new CreateFileRequest();
        createFileRequest.setCheckNameMode("auto_rename");
        createFileRequest.setDriverId(STORE_BUCKET.getDriveId());
        createFileRequest.setCreateScene("file_upload");
        createFileRequest.setName(fileName);
        createFileRequest.setParentFileId(targetParentFileId);
        createFileRequest.setPartInfoList(buildPartInfoList(size));
        createFileRequest.setPreHash(preHash);
        createFileRequest.setSize(size + "");
        createFileRequest.setType("file");
        JSONObject resp = getAuthRequestBody(FILE_CREATE_WITH_FOLDERS_URL, JSONObject.parseObject(JSON.toJSONString(createFileRequest)));
        return resp;
    }

    private JSONObject contentHash(File file, String fileName, String targetParentFileId) throws Exception {
        String cHash = VideoFileUtil.calculateSHA1ByChunk(file, (int) UPLOAD_CHUNK_SIZE);
        String proof = calculateProof(file);

        long size = file.length();
        CreateFileRequest createFileRequest = new CreateFileRequest();
        createFileRequest.setCheckNameMode("auto_rename");
        createFileRequest.setDriverId(STORE_BUCKET.getDriveId());
        createFileRequest.setName(fileName);
        createFileRequest.setParentFileId(targetParentFileId);
        createFileRequest.setPartInfoList(buildPartInfoList(size));
        createFileRequest.setContentHash(cHash);
        createFileRequest.setContentHashName("sha1");
        createFileRequest.setSize(size + "");
        createFileRequest.setType("file");
        createFileRequest.setProofCode(proof);
        createFileRequest.setProofVersion("v1");
        return getAuthRequestBody(FILE_CREATE_WITH_FOLDERS_URL, JSONObject.parseObject(JSON.toJSONString(createFileRequest)));
    }

    /**
     * 不能多线程上传，必须顺序上传，会报错
     *
     * @param localVideo
     * @param fileSize
     * @param fileInfo
     * @return
     * @throws Exception
     */
    private RemoteSeverVideo uploadByChunk( File localVideo, long fileSize, CreateFileResponse fileInfo ) throws Exception {
        int partCount = (int) Math.ceil(fileSize * 1.0 / UPLOAD_CHUNK_SIZE);
        log.info("video size is {}M, seg {} parts to upload.", fileSize / 1024 / 1024, partCount);
        CountDownLatch countDownLatch = new CountDownLatch(partCount);
        List<Integer> failChunkNums = Lists.newCopyOnWriteArrayList();
        // 用于标记是否有任务失败
        AtomicBoolean hasFailed = new AtomicBoolean(false);

        for (int i = 0; i < partCount; i++) {
            //当前分段起始位置
            long curChunkStart = (long) i * UPLOAD_CHUNK_SIZE;
            // 当前分段大小  如果为最后一个大小为fileSize-curChunkStart  其他为partSize
            long curChunkSize = (i + 1 == partCount) ? (fileSize - curChunkStart) : UPLOAD_CHUNK_SIZE;
            String uploadUrl = fileInfo.getPartInfoList().get(i).getUploadUrl();

            boolean uploadChunkSuccess = uploadChunk(uploadUrl, localVideo, i, partCount, (int) curChunkSize, curChunkStart);
            if (!uploadChunkSuccess) {
                failChunkNums.add(i);
                break;
            }
        }

        if (CollectionUtils.isEmpty(failChunkNums)) {
            log.info("video chunks upload success, videoPath: {}", localVideo.getAbsolutePath());
        } else {
            log.error("video chunks upload fail, failed chunkNos: {}", JSON.toJSONString(failChunkNums));
            return null;
        }


        // 2. 调用完成整个视频上传
        CompleteFileRequest completeFileRequest = new CompleteFileRequest();
        completeFileRequest.setFileId(fileInfo.getFileId());
        completeFileRequest.setDriverId(fileInfo.getDriveId());
        completeFileRequest.setUploadId(fileInfo.getUploadId());
        completeFileRequest.setPartInfoList(fileInfo.getPartInfoList());

        JSONObject competeFileObj = getAuthRequestBody(FILE_COMPLETE_URL, JSONObject.parseObject(JSON.toJSONString(completeFileRequest)));
        AliFileDTO aliFile = competeFileObj.toJavaObject(AliFileDTO.class);
        if (aliFile == null || StringUtils.isBlank(aliFile.getFileId())) {
            log.error("file upload to aliDriver failed, resp: {}", competeFileObj.toJSONString());
            return null;
        }

        return new RemoteSeverVideo(aliFile.getFileId(), localVideo.getAbsolutePath());
    }

    private boolean uploadChunk( String uploadUrl, File targetFile, int index, Integer totalChunks, Integer curChunkSize, Long curChunkStart ) {
        int chunkShowNo = index + 1;
        long startTime = System.currentTimeMillis();

        byte[] bytes = null;
        try {
            bytes = VideoFileUtil.fetchBlock(targetFile, curChunkStart, curChunkSize);
        } catch (IOException e) {
            log.error("fetch chunk error", e);
            return false;
        }
        HttpEntity requestEntity = new ByteArrayEntity(bytes);

        for (int i = 0; i < RETRY_COUNT; i++) {
            try {
                String resp = HttpClientUtil.sendPut(uploadUrl, null, requestEntity, false);
                if (StringUtils.isBlank(resp)) {
                    log.info("{} chunk upload success, progress: {}/{}, time cost: {}s.", getType(), chunkShowNo,
                            totalChunks, (System.currentTimeMillis() - startTime) / 1000);
                    return true;
                } else {
                    log.error("{}th chunk upload failed, retry: {}/{}, resp: {}", chunkShowNo, i + 1, RETRY_COUNT, resp);
                }
            } catch (Exception e) {
                log.error("{}th chunk upload error, retry: {}/{}", chunkShowNo, i + 1, RETRY_COUNT, e);
//                try {
//                    Thread.sleep(CHUNK_RETRY_DELAY);
//                } catch (InterruptedException ex) {
//                }
//                GetUploadUrlRequest uploadUrlRequest = new GetUploadUrlRequest();
//                uploadUrlRequest.setDriverId(fileInfo.getDriveId());
//                uploadUrlRequest.setFileId(fileInfo.getFileId());
//                uploadUrlRequest.setUploadId(fileInfo.getUploadId());
//                uploadUrlRequest.setPartInfoList(fileInfo.getPartInfoList().stream()
//                        .map(p -> new UploadPartInfo(p.getPartNumber()))
//                        .collect(Collectors.toList()));
//
//                fileInfo = getAuthRequestBody(FILE_GET_UPLOAD_URL, JSONObject.parseObject(JSON.toJSONString(uploadUrlRequest)))
//                        .toJavaObject(CreateFileResponse.class);
            }
        }
        return false;
    }


    private String calculateProof(File file) throws Exception {
        String md5 = AliDriverUtil.md5(STORE_BUCKET.getAccessToken().getBytes(StandardCharsets.UTF_8));
        BigInteger preMd5 = new BigInteger(md5.substring(0, 16), 16);
        BigInteger length = new BigInteger(String.valueOf(file.length()));
        long start = preMd5.mod(length).intValue();
        long end = Math.min(start + 8, file.length());
        return Base64.getEncoder().encodeToString(VideoFileUtil.fetchBlock(file, start, (int) (end - start)));
    }

    private List<UploadPartInfo> buildPartInfoList(long fileSize) {
        int chunkNum = (int) Math.ceil(fileSize * 1.0 / UPLOAD_CHUNK_SIZE);
        List<UploadPartInfo> res = Lists.newArrayList();
        for (int i = 1; i <= chunkNum; i++) {
            res.add(new UploadPartInfo(i));
        }
        return res;
    }

    private JSONObject getAuthRequestBody(String url, JSONObject data) {
        Map<String, String> headers = Maps.newHashMap();
        headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/102.0.5005.63 Safari/537.36");
        headers.put("Referer", "https://www.aliyundrive.com/");
        headers.put("authorization", "Bearer " + STORE_BUCKET.getAccessToken());

        String resp = HttpClientUtil.sendPost(url, headers, data);
        return JSONObject.parseObject(resp);
    }
}
