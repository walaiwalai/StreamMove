package com.sh.engine.upload;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.sh.config.manager.ConfigFetcher;
import com.sh.config.model.video.FailUploadVideoChunk;
import com.sh.config.model.video.LocalVideo;
import com.sh.config.utils.FileChunkIterator;
import com.sh.config.utils.HttpClientUtil;
import com.sh.config.utils.VideoFileUtils;
import com.sh.engine.base.StreamerInfoHolder;
import com.sh.engine.model.alidriver.*;
import com.sh.engine.model.upload.BaseUploadTask;
import com.sh.engine.util.AliDriverUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpEntity;
import org.apache.http.entity.ByteArrayEntity;
import org.springframework.stereotype.Component;

import java.io.File;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * @Author caiwen
 * @Date 2024 03 09 18 23
 **/
@Slf4j
@Component
public class AliDriverUploadServiceImpl extends AbstractWorkUploadService {
    private static AliStoreBucket STORE_BUCKET;
    /**
     * 分块上传大小为10M
     */
    private static final long UPLOAD_CHUNK_SIZE = 1024 * 1024 * 10;
    private static final String FILE_CREATE_WITH_FOLDERS_URL = "https://api.aliyundrive.com/adrive/v2/file/createWithFolders";
    private static final String FILE_GET_UPLOAD_URL = "https://api.aliyundrive.com/v2/file/get_upload_url";
    private static final String FILE_COMPLETE_URL = "https://api.aliyundrive.com/v2/file/complete";
    private static final int RETRY_COUNT = 3;
    public static final int CHUNK_RETRY_DELAY = 500;

    static {
        // 根据refreshToken拿到信息阿里云盘基本信息
        String refreshToken = ConfigFetcher.getInitConfig().getRefreshToken();
        if (StringUtils.isNotBlank(refreshToken)) {
            STORE_BUCKET = new AliStoreBucket(refreshToken);
            STORE_BUCKET.refreshToken();
            log.info("init aliDriver param success, dirverId: {}, userId: {}", STORE_BUCKET.getDriveId(), STORE_BUCKET.getUserId());
        }
    }

    @Override
    public String getName() {
        return "ALI_DRIVER";
    }

    @Override
    public boolean upload(List<LocalVideo> localVideos, BaseUploadTask task) throws Exception {
        String targetFileId = ConfigFetcher.getInitConfig().getTargetFileId();
        for (LocalVideo localVideo : localVideos) {
            if (!StringUtils.equals("highlight", localVideo.getTitle())) {
                continue;
            }
            String fileName = StreamerInfoHolder.getCurStreamerName() + "_" + localVideo.getTitle() + "_" + System.currentTimeMillis() + ".mp4";
            uploadFile(targetFileId, fileName, new File(localVideo.getLocalFileFullPath()));
        }
        return true;
    }


    public static void main(String[] args) throws Exception {
        AliDriverUploadServiceImpl service = new AliDriverUploadServiceImpl();
        service.upload(Lists.newArrayList(LocalVideo.builder()
//                .localFileFullPath("/Users/caiwen/Desktop/download/TheShy/2024-01-31-03-31-43/seg-1.ts")
                .localFileFullPath("G:\\graduate\\process\\pro_nj020.csv")
                .title("highlight")
                .build()), BaseUploadTask.builder().build());
    }

    private boolean uploadFile(String parentId, String fileName, File file) throws Exception {
        long fileSize = file.length();

        // 1. 进行pre_hash
        JSONObject preHashRet = preHash(file, fileName, parentId);

        // 2. 进行content_hash
        CreateFileResponse response = contentHash(file, fileName, parentId).toJavaObject(CreateFileResponse.class);
        if (response.isRapidUpload() || response.isExist()) {
            // 极速上传或文件已经存在，跳过
            log.info("rapid upload file or file existed, file: {}", file.getAbsolutePath());
            return true;
        }

        // 3.分块进行上传
        boolean success = uploadByChunk(file, fileSize, response);
        return success;
    }

    private JSONObject preHash(File uploadFile, String fileName, String targetParentFileId) throws Exception {
        byte[] buff = VideoFileUtils.fetchBlock(uploadFile, 0, 1024);
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
        String cHash = VideoFileUtils.calculateSHA1ByChunk(file, (int) UPLOAD_CHUNK_SIZE);
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
     * 不能多线程上传，会报错
     *
     * @param localVideo
     * @param fileSize
     * @param fileInfo
     * @return
     * @throws Exception
     */
    private boolean uploadByChunk(File localVideo, long fileSize, CreateFileResponse fileInfo) throws Exception {
        int partCount = (int) Math.ceil(fileSize * 1.0 / UPLOAD_CHUNK_SIZE);
        log.info("video size is {}M, seg {} parts to upload.", fileSize / 1024 / 1024, partCount);

        List<FailUploadVideoChunk> failUploadVideoChunks = Lists.newCopyOnWriteArrayList();
        int i = 0;
        try (FileChunkIterator chunkIterator = new FileChunkIterator(localVideo.getAbsolutePath(), (int) UPLOAD_CHUNK_SIZE)) {
            for (byte[] chunk : chunkIterator.iterateChunks()) {
                if (chunk == null) {
                    break;
                }
                long curChunkStart = (long) i * UPLOAD_CHUNK_SIZE;
                long curChunkSize = (i + 1 == partCount) ? (fileSize - curChunkStart) : UPLOAD_CHUNK_SIZE;
                boolean uploadChunkSuccess = uploadChunk(fileInfo, i, chunk, partCount);
                if (!uploadChunkSuccess) {
                    FailUploadVideoChunk failUploadVideoChunk = new FailUploadVideoChunk();
                    failUploadVideoChunk.setChunkStart(curChunkStart);
                    failUploadVideoChunk.setCurChunkSize(curChunkSize);
                    failUploadVideoChunk.setChunkNo(i);
                    failUploadVideoChunks.add(failUploadVideoChunk);
                }
                i++;
            }
        }

        if (CollectionUtils.isEmpty(failUploadVideoChunks)) {
            log.info("video chunks upload success, videoPath: {}", localVideo.getAbsolutePath());
        } else {
            log.error("video chunks upload fail, failed chunkNos: {}", failUploadVideoChunks.stream().map(
                    FailUploadVideoChunk::getChunkNo).collect(Collectors.toList()));
            return false;
        }

        // 2. 调用完成整个视频上传
        CompleteFileRequest completeFileRequest = new CompleteFileRequest();
        completeFileRequest.setFileId(fileInfo.getFileId());
        completeFileRequest.setDriverId(fileInfo.getDriveId());
        completeFileRequest.setUploadId(fileInfo.getUploadId());
        completeFileRequest.setPartInfoList(fileInfo.getPartInfoList());

        AliFileDTO aliFile = getAuthRequestBody(FILE_COMPLETE_URL, JSONObject.parseObject(JSON.toJSONString(completeFileRequest))).toJavaObject(AliFileDTO.class);
        if (aliFile != null) {
            log.info("file upload to aliDriver success, file: {}", JSON.toJSONString(aliFile));
        } else {
            log.error("file upload to aliDriver failed, localFile: {}", JSON.toJSONString(localVideo));
        }

        return aliFile != null;
    }

    private boolean uploadChunk(CreateFileResponse fileInfo, int index, byte[] bytes, Integer totalChunks) {
        long startTime = System.currentTimeMillis();
        HttpEntity requestEntity = new ByteArrayEntity(bytes);

        for (int i = 0; i < RETRY_COUNT; i++) {
            String uploadUrl = fileInfo.getPartInfoList().get(index).getUploadUrl();
            try {
                HttpClientUtil.sendPut(uploadUrl, null, requestEntity, true);
                log.info("chunk upload success, progress: {}/{}, time cost: {}s.", index + 1, totalChunks,
                        (System.currentTimeMillis() - startTime) / 1000);
                return true;
            } catch (Exception e) {
                try {
                    Thread.sleep(CHUNK_RETRY_DELAY);
                } catch (InterruptedException ex) {
                }
                GetUploadUrlRequest uploadUrlRequest = new GetUploadUrlRequest();
                uploadUrlRequest.setDriverId(fileInfo.getDriveId());
                uploadUrlRequest.setFileId(fileInfo.getFileId());
                uploadUrlRequest.setUploadId(fileInfo.getUploadId());
                uploadUrlRequest.setPartInfoList(fileInfo.getPartInfoList().stream()
                        .map(p -> new UploadPartInfo(p.getPartNumber()))
                        .collect(Collectors.toList()));

                fileInfo = getAuthRequestBody(FILE_GET_UPLOAD_URL, JSONObject.parseObject(JSON.toJSONString(uploadUrlRequest)))
                        .toJavaObject(CreateFileResponse.class);
                log.error("{}th chunk upload error, retry: {}/{}", index + 1, i + 1, RETRY_COUNT, e);
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
        return Base64.getEncoder().encodeToString(VideoFileUtils.fetchBlock(file, start, (int) (end - start)));
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
