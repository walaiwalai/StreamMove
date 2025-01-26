package com.sh.engine.model.alidriver;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.sh.config.utils.HttpClientUtil;
import com.sh.config.utils.VideoFileUtil;
import com.sh.engine.util.AliDriverUtil;

import java.io.File;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * 阿里云盘的仓库类
 */

public class AliStoreBucket {
    private static final String REFRESH_TOKEN_URL = "https://api.aliyundrive.com/token/refresh";
    private static final String FILE_CREATE_WITH_FOLDERS_URL = "https://api.aliyundrive.com/adrive/v2/file/createWithFolders";
    private static final String FILE_COMPLETE_URL = "https://api.aliyundrive.com/v2/file/complete";
    private static final String FILE_GET_UPLOAD_URL = "https://api.aliyundrive.com/v2/file/get_upload_url";


    /**
     * 分块上传大小为10M
     */
    private static final long UPLOAD_CHUNK_SIZE = 1024 * 1024 * 5;

    private String refreshToken;
    private String driveId;
    private String userId;
    private String rootId;
    private String accessToken;
    private long accessTokenTime = 0;


    public String getDriveId() {
        return driveId;
    }

    public String getRefreshToken() {
        return refreshToken;
    }


    public String getRootId() {
        return rootId;
    }

    public String getAccessToken() {
        // accessToken 有效期是7200秒
        if (accessTokenTime + 7000 * 1000 < System.currentTimeMillis()) {
            refreshToken();
        }
        return accessToken;
    }

    public long getAccessTokenTime() {
        return accessTokenTime;
    }

    public String getUserId() {
        return userId;
    }

    public AliStoreBucket(String refreshToken) {
        this.refreshToken = refreshToken;
        rootId = "root";
        refreshToken();
    }


    public void preHash(File uploadFile, String fileName, String targetParentFileId) throws Exception {
        byte[] buff = VideoFileUtil.fetchBlock(uploadFile, 0, 1024);
        String preHash = AliDriverUtil.sha1(buff, 0, 1024);

        long size = uploadFile.length();
        CreateFileRequest createFileRequest = new CreateFileRequest();
        createFileRequest.setCheckNameMode("auto_rename");
        createFileRequest.setDriverId(driveId);
        createFileRequest.setCreateScene("file_upload");
        createFileRequest.setName(fileName);
        createFileRequest.setParentFileId(targetParentFileId);
        createFileRequest.setPartInfoList(buildPartInfoList(size));
        createFileRequest.setPreHash(preHash);
        createFileRequest.setSize(size + "");
        createFileRequest.setType("file");
        setPost(FILE_CREATE_WITH_FOLDERS_URL, JSONObject.parseObject(JSON.toJSONString(createFileRequest)));
    }

    public CreateFileResponse contentHash(File file, String fileName, String targetParentFileId) throws Exception {
        String cHash = VideoFileUtil.calculateSHA1ByChunk(file, (int) UPLOAD_CHUNK_SIZE);
        String proof = calculateProof(file);

        long size = file.length();
        CreateFileRequest createFileRequest = new CreateFileRequest();
        createFileRequest.setCheckNameMode("auto_rename");
        createFileRequest.setDriverId(getDriveId());
        createFileRequest.setName(fileName);
        createFileRequest.setParentFileId(targetParentFileId);
        createFileRequest.setPartInfoList(buildPartInfoList(size));
        createFileRequest.setContentHash(cHash);
        createFileRequest.setContentHashName("sha1");
        createFileRequest.setSize(size + "");
        createFileRequest.setType("file");
        createFileRequest.setProofCode(proof);
        createFileRequest.setProofVersion("v1");

        String resp = setPost(FILE_CREATE_WITH_FOLDERS_URL, JSONObject.parseObject(JSON.toJSONString(createFileRequest)));
        return JSON.parseObject(resp, CreateFileResponse.class);
    }

    public CreateFileResponse getUploadUrl(GetUploadUrlRequest request) {
        String resp = setPost(FILE_GET_UPLOAD_URL, JSONObject.parseObject(JSON.toJSONString(request)));
        return JSON.parseObject(resp, CreateFileResponse.class);
    }

    public AliFileDTO complete(CreateFileResponse fileInfo) {
        CompleteFileRequest completeFileRequest = new CompleteFileRequest();
        completeFileRequest.setFileId(fileInfo.getFileId());
        completeFileRequest.setDriverId(fileInfo.getDriveId());
        completeFileRequest.setUploadId(fileInfo.getUploadId());
        completeFileRequest.setPartInfoList(fileInfo.getPartInfoList());
        String resp = setPost(FILE_COMPLETE_URL, JSONObject.parseObject(JSON.toJSONString(completeFileRequest)));
        return JSON.parseObject(resp, AliFileDTO.class);
    }

    private String setPost(String url, JSONObject data) {
        Map<String, String> headers = Maps.newHashMap();
        headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/102.0.5005.63 Safari/537.36");
        headers.put("Referer", "https://www.aliyundrive.com/");
        headers.put("authorization", "Bearer " + getAccessToken());

        String resp = HttpClientUtil.sendPost(url, headers, data);
        return resp;
    }

    private String calculateProof(File file) throws Exception {
        String md5 = AliDriverUtil.md5(getAccessToken().getBytes(StandardCharsets.UTF_8));
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

    public void refreshToken() {
        Map<String, String> headers = Maps.newHashMap();
        headers.put("Content-Type", "application/json;charset=UTF-8");
        Map<String, String> data = Maps.newHashMap();
        data.put("refresh_token", refreshToken);
        String resp = HttpClientUtil.sendPost(REFRESH_TOKEN_URL, headers, data);

        JSONObject object = JSONObject.parseObject(resp);
        accessToken = object.getString("access_token");
        refreshToken = object.getString("refresh_token");
        driveId = object.getString("default_drive_id");
        userId = object.getString("user_id");
        accessTokenTime = System.currentTimeMillis();
    }
}
