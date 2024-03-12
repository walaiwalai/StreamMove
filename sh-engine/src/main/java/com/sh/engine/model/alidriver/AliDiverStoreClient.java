package com.sh.engine.model.alidriver;

import cn.hutool.core.lang.Assert;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.Lists;
import com.sh.config.utils.VideoFileUtils;
import com.sh.engine.util.AliDriverUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;

import javax.net.ssl.HttpsURLConnection;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/**
 * 实现阿里云盘的爬虫
 */
@Slf4j
public class AliDiverStoreClient {

    /**
     * 阿里云盘仓库，一个阿里账号有一个存储仓库
     */
    private AliStoreBucket aliStoreBucket;

    /**
     * 分块上传大小为10M
     */
    private static final long UPLOAD_CHUNK_SIZE = 1024 * 1024 * 10;
    private static final String FILE_CREATE_WITH_FOLDERS_URL = "https://api.aliyundrive.com/adrive/v2/file/createWithFolders";

    public AliDiverStoreClient(AliStoreBucket aliStoreBucket) {
        Assert.notNull(aliStoreBucket, "bucket can not be null");
        this.aliStoreBucket = aliStoreBucket;
        this.getAccessToken();
    }

    /**
     * 列举目录下面的所有文件
     *
     * @param parentId 目录id
     * @return
     * @throws Exception
     */
    public List<AliFileDTO> listAllObject(String parentId) throws Exception {
        final String api = "https://api.aliyundrive.com/adrive/v3/file/list?jsonmask=next_marker%2Citems(name%2Cfile_id%2Curl)";
        String marker = "";
        List<AliFileDTO> res = new ArrayList<>(256);
        while (true) {
            String data = "  {" +
                    "    \"all\": false," +
                    "    \"drive_id\": \"" + aliStoreBucket.getDriveId() + "\"," +
                    "    \"fields\": \"*\"," +
                    "    \"image_thumbnail_process\": \"\"," +
                    "    \"image_url_process\": \"\"," +
                    "    \"limit\": 100,\n" +
                    "    \"order_by\": \"name\"," +
                    "    \"order_direction\": \"ASC\"," +
                    "    \"parent_file_id\": \"" + parentId + "\"," +
                    "    \"url_expire_sec\": 86400," +
                    "    \"video_thumbnail_process\": \"\"," +
                    "    \"marker\": \"" + marker + "\"" +
                    "  }";

            JSONObject ret = getAuthRequestBody(api, data);
            marker = ret.getString("next_marker");
            List<AliFileDTO> list = ret.getJSONArray("items").toJavaList(AliFileDTO.class);
            res.addAll(list);
            if (list.size() < 100) {
                break;
            }
        }
        return res;
    }

    /**
     * 创建目录
     *
     * @param parentId   目录的位置
     * @param folderName 目录名称
     * @return 创建的结果
     * @throws Exception
     */
    public JSONObject createFolder(String parentId, String folderName) throws Exception {
        return putObject(parentId, folderName, "".getBytes(StandardCharsets.UTF_8), "folder");
    }

    /**
     * 上传文件
     *
     * @param parentId 上传到的位置
     * @param fileName 文件名称
     * @param content  文件内容
     * @return
     * @throws Exception
     */
    public JSONObject putFile(String parentId, String fileName, byte[] content) throws Exception {
        return putObject(parentId, fileName, content, "file");
    }

    public boolean uploadFile(String parentId, String fileName, File file) throws Exception {
        long fileSize = file.length();

        // 1. 进行pre_hash
        JSONObject preHashRet = preHash(file, fileName, parentId);
        String uploadId = preHashRet.getString("upload_id");
        String fileId = preHashRet.getString("file_id");

        // 2. 进行content_hash
        CreateFileResponse response = contentHash(file, fileName, parentId).toJavaObject(CreateFileResponse.class);
        if (response.isRapidUpload() || response.isExist()) {
            // 极速上传或文件已经存在，跳过
            log.info("rapid upload file or file existed, file: {}", file.getAbsolutePath());
            return true;
        }

        // 3.分块进行上传

        return false;
    }


    private List<UploadPartInfo> buildPartInfoList(long fileSize) {
        int chunkNum = (int) Math.ceil(fileSize * 1.0 / UPLOAD_CHUNK_SIZE);
        List<UploadPartInfo> res = Lists.newArrayList();
        for (int i = 1; i <= chunkNum; i++) {
            res.add(new UploadPartInfo(i));
        }
        return res;
    }

    /**
     * 上传超大文件
     *
     * @param parentId 上传到的位置
     * @param fileName 文件名
     * @param file     本地的文件
     * @return
     * @throws Exception
     */
    public JSONObject putBigFile(String parentId, String fileName, File file) throws Exception {
        // 1.进行pre_hash
        JSONObject ret = preHash(file, fileName, parentId);
        String uploadId = ret.getString("upload_id");
        String fileId = ret.getString("file_id");
        List<UploadPartInfo> partInfoList = ret.getJSONArray("part_info_list").toJavaList(UploadPartInfo.class);


        String completeData = "{\"drive_id\":\"" + aliStoreBucket.getDriveId() + "\",\"upload_id\":\"" + uploadId + "\",\"file_id\":\"" + fileId + "\"}";
        ret = getAuthRequestBody("https://api.aliyundrive.com/v2/file/complete", completeData);
        return ret;
    }


    private JSONObject preHash(File uploadFile, String fileName, String targetParentFileId) throws Exception {
        byte[] buff = VideoFileUtils.fetchBlock(uploadFile, 0, 1024);
        String preHash = AliDriverUtil.sha1(buff, 0, 1024);

        long size = uploadFile.length();
        CreateFileRequest createFileRequest = new CreateFileRequest();
        createFileRequest.setCheckNameMode("auto_rename");
        createFileRequest.setDriverId(aliStoreBucket.getDriveId());
        createFileRequest.setCreateScene("file_upload");
        createFileRequest.setName(fileName);
        createFileRequest.setParentFileId(targetParentFileId);
        createFileRequest.setPartInfoList(buildPartInfoList(size));
        createFileRequest.setPreHash(preHash);
        createFileRequest.setSize(size + "");
        createFileRequest.setType("file");
        JSONObject resp = getAuthRequestBody(FILE_CREATE_WITH_FOLDERS_URL, JSON.toJSONString(createFileRequest));
        return resp;
    }

    private JSONObject contentHash(File file, String fileName, String targetParentFileId) throws Exception {
        String cHash = VideoFileUtils.calculateSHA1ByChunk(file, (int) UPLOAD_CHUNK_SIZE);
        String proof = calculateProof(file);

        long size = file.length();
        CreateFileRequest createFileRequest = new CreateFileRequest();
        createFileRequest.setCheckNameMode("auto_rename");
        createFileRequest.setDriverId(aliStoreBucket.getDriveId());
        createFileRequest.setName(fileName);
        createFileRequest.setParentFileId(targetParentFileId);
        createFileRequest.setPartInfoList(buildPartInfoList(size));
        createFileRequest.setContentHash(cHash);
        createFileRequest.setContentHashName("sha1");
        createFileRequest.setSize(size + "");
        createFileRequest.setType("file");
        createFileRequest.setProofCode(proof);
        createFileRequest.setProofVersion("v1");
        return getAuthRequestBody(FILE_CREATE_WITH_FOLDERS_URL, JSON.toJSONString(createFileRequest));

//        boolean readNext = false;
//        for (int i = 1; i <= end; ) {
//            for (AliUploadPartInfo item : partInfoList) {
//                if (item.getPartNumber() != i) {
//                    throw new RuntimeException("序号异常");
//                }
//                if (readNext) {
//                    n = inputStream.read(buff);
//                    readNext = false;
//                }
//                try {
//                    writeData(item.getUploadUrl(), buff, 0, n);
//                    readNext = true;
//                } catch (Exception e) {
//                    log.error("网络异常：5秒后重试");
//                    Thread.sleep(5000);
//                    break;
//                }
//                i++;
//            }
//            if (i <= end) {
//                for (int j = 0; j < 3; j++) {
//                    try {
//                        partInfoList = getUploadUrl(uploadId, fileId, i, Math.min(i + 9, end));
//                        break;
//                    } catch (Exception e) {
//                        if (j == 2) {
//                            return null;
//                        }
//                        System.out.println("网络异常," + (20 * j) + "秒后重试");
//                        Thread.sleep(20000 * j);
//                    }
//                }
//            }
//        }
//        inputStream.close();
    }


    public byte[] getFileContent(String fileId) throws Exception {
        String url = generateUrl(fileId);
        HttpsURLConnection connection = (HttpsURLConnection) new URL(url).openConnection();
        connection.addRequestProperty("Referer", "https://www.aliyundrive.com/");
        return IOUtils.toByteArray(connection.getInputStream());
    }


    private String getAccessToken() {
        //accessToken 有效期是7200秒
        if (aliStoreBucket.getAccessTokenTime() + 7000 * 1000 < System.currentTimeMillis()) {
            aliStoreBucket.refreshToken();
        }
        return aliStoreBucket.getAccessToken();
    }

    /**
     * 删除文件
     *
     * @param fileId 文件id
     * @return
     * @throws Exception
     */
    public JSONObject deleteObject(String fileId) throws Exception {
        final String api = "https://api.aliyundrive.com/v3/file/delete";
        String data = "{\"drive_id\":\"" + aliStoreBucket.getDriveId() + "\",\"file_id\":\"" + fileId + "\"}";
        return getAuthRequestBody(api, data);
    }


    /**
     * 获取根目录的id
     */
    public String getRootId() {
        return aliStoreBucket.getRootId();
    }

    /**
     * 计算文件的Proof
     *
     * @param content 文件上内容
     * @return Proof
     * @throws Exception
     */
    private String calculateProof(byte[] content) throws Exception {
        if (content.length == 0) {
            return "";
        }
        String md5 = AliDriverUtil.md5(getAccessToken().getBytes(StandardCharsets.UTF_8));
        BigInteger preMd5 = new BigInteger(md5.substring(0, 16), 16);
        BigInteger length = new BigInteger(String.valueOf(content.length));
        int start = preMd5.mod(length).intValue();
        int end = Math.min(start + 8, content.length);
        return Base64.getEncoder().encodeToString(Arrays.copyOfRange(content, start, end));
    }

    private String calculateProof(File file) throws Exception {
        if (!file.exists()) {
            return "";
        }
        String md5 = AliDriverUtil.md5(getAccessToken().getBytes(StandardCharsets.UTF_8));
        BigInteger preMd5 = new BigInteger(md5.substring(0, 16), 16);
        BigInteger length = new BigInteger(String.valueOf(file.length()));
        long start = preMd5.mod(length).intValue();
        long end = Math.min(start + 8, file.length());
        return Base64.getEncoder().encodeToString(VideoFileUtils.fetchBlock(file, start, (int) (end - start)));
    }

    /**
     * 列出目录的文件。只返回前50个
     *
     * @param parentId 目录id,根目录id为root
     * @param field    字段id,指定返回文件包含的字段信息
     *                 如 items(drive_id,file_id,size,url,content_hash,name)
     */
    private JSONObject listObject(String parentId, String field) throws Exception {
        final String api = "https://api.aliyundrive.com/adrive/v3/file/list?jsonmask=" + URLEncoder.encode(field, "UTF-8");
        String data = "  {" +
                "    \"all\": false," +
                "    \"drive_id\": \"" + aliStoreBucket.getDriveId() + "\"," +
                "    \"fields\": \"*\"," +
                "    \"image_thumbnail_process\": \"\"," +
                "    \"image_url_process\": \"\"," +
                "    \"limit\": 50,\n" +
                "    \"order_by\": \"updated_at\"," +
                "    \"order_direction\": \"DESC\"," +
                "    \"parent_file_id\": \"" + parentId + "\"," +
                "    \"url_expire_sec\": 14400," +
                "    \"video_thumbnail_process\": \"\"" +
                "  }";
        return getAuthRequestBody(api, data);
    }

    private String buildPartInfoList(int start, int end) {
        StringBuilder builder = new StringBuilder();
        builder.append("[");
        for (int i = start; i <= end; i++) {
            builder.append("{\"part_number\":" + i + "},");
        }
        builder.deleteCharAt(builder.length() - 1);
        builder.append("]");
        return builder.toString();
    }


    private List<UploadPartInfo> getUploadUrl(String uploadId, String fileId, int start, int end) throws Exception {
        final String api = "https://api.aliyundrive.com/v2/file/get_upload_url";
        String data = "{\"drive_id\":\"" + aliStoreBucket.getDriveId() + "\",\"upload_id\":\"" + uploadId + "\",\"file_id\":\"" + fileId + "\",\"part_info_list\":" + buildPartInfoList(start, end) + "}";
        JSONObject ret = getAuthRequestBody(api, data);
        List<UploadPartInfo> partInfoList = ret
                .getJSONArray("part_info_list")
                .toJavaList(UploadPartInfo.class);
        return partInfoList;
    }


    private JSONObject putObject(String parentId, String fileName, byte[] content, String type) throws Exception {
        final String api = "https://api.aliyundrive.com/adrive/v2/file/createWithFolders";
        String sha1 = AliDriverUtil.sha1(content);
        String proof = calculateProof(content);
        String data = "{" +
                "  \"check_name_mode\": \"auto_rename\"," +
                "  \"content_hash\": \"" + sha1 + "\"," +
                "  \"content_hash_name\": \"sha1\"," +
                "  \"drive_id\": \"" + aliStoreBucket.getDriveId() + "\"," +
                "  \"name\": \"" + fileName + "\"," +
                "  \"parent_file_id\": \"" + parentId + "\"," +
                "  \"part_info_list\": [" +
                "    {" +
                "      \"part_number\": 1" +
                "    }" +
                "  ]," +
                "  \"proof_code\": \"" + proof + "\"," +
                "  \"proof_version\": \"v1\"," +
                "  \"size\": " + content.length + "," +
                "  \"type\": \"" + type + "\"" +
                "}";

        JSONObject ret = getAuthRequestBody(api, data);
        if (type.equals("folder")) {
            return ret;
        }
        if (ret.getJSONArray("part_info_list") == null) {
            ret.put("fastUpload", true);
            return ret;
        }
        String uploadUrl = ret.getJSONArray("part_info_list").getJSONObject(0).getString("upload_url");
        writeData(uploadUrl, content);
        String data2 = "{\"drive_id\":\"" + aliStoreBucket.getDriveId() + "\",\"upload_id\":\"" + ret.getString("upload_id") + "\",\"file_id\":\"" + ret.getString("file_id") + "\"}";
        return getAuthRequestBody("https://api.aliyundrive.com/v2/file/complete", data2);
    }

    private String writeData(String url, byte[] content) throws Exception {
        return writeData(url, content, 0, content.length);
    }

    private String writeData(String url, byte[] content, int offset, int length) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setDoOutput(true);
        connection.setRequestMethod("PUT");
        connection.addRequestProperty("Referer", " https://www.aliyundrive.com/");
        OutputStream outputStream = connection.getOutputStream();
        outputStream.write(content, offset, length);
        outputStream.flush();
        try {
            connection.getInputStream();
            return null;
        } catch (Exception e) {
            InputStream errorStream = connection.getErrorStream();
            String s = new String(IOUtils.toByteArray(errorStream));
            throw new Exception(s);
        }
    }


    /**
     * 获取文件的下载url
     *
     * @param fileId 文件id
     * @return
     * @throws Exception
     */
    public String generateUrl(String fileId) throws Exception {

        final String api = "https://api.aliyundrive.com/v2/file/get_download_url";
        String data = "{\"drive_id\":\"" + aliStoreBucket.getDriveId() + "\",\"file_id\":\"" + fileId + "\"}";
        JSONObject ret = getAuthRequestBody(api, data);
        String url = ret.getString("url");
        if (url == null || url.equals("")) {
            throw new Exception("无法匹配到url:" + ret);
        }
        return url;
    }

    private JSONObject getAuthRequestBody(String url, String data) {
//        HttpsURLConnection connection = null;
//        try {
//            connection = (HttpsURLConnection) new URL(url).openConnection();
//            connection.addRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/102.0.5005.63 Safari/537.36");
//            connection.addRequestProperty("Referer", "https://www.aliyundrive.com/");
//            connection.addRequestProperty("authorization", "Bearer " + getAccessToken());
////            connection.addRequestProperty("X-Device-Id", xDeviceId);
////            connection.addRequestProperty("X-Signature", AliDriverUtil.genSignature(xDeviceId, aliStoreBucket.getUserId()));
//            connection.setDoOutput(true);
//            OutputStream outputStream = connection.getOutputStream();
//            outputStream.write(data.getBytes(StandardCharsets.UTF_8));
//            outputStream.flush();
//            String s = new String(IOUtils.toByteArray(connection.getInputStream()));
//            return JSONObject.parseObject(s);
//        } catch (IOException e) {
//            if (connection == null) {
//                throw new RuntimeException("connect 出错");
//            }
//            InputStream errorStream = connection.getErrorStream();
//            String s = new String(IOUtils.toByteArray(errorStream));
//            throw new RuntimeException(s);
//        }
        return null;
    }
}
