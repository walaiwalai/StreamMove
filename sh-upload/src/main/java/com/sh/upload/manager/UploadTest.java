package com.sh.upload.manager;

import cn.hutool.core.io.FileUtil;
import cn.hutool.http.HttpUtil;
import com.alibaba.fastjson.JSON;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.sh.config.utils.VideoFileUtils;
import com.sh.config.utils.HttpClientUtil;
import com.sh.upload.model.upload.BlockStreamBody;
import com.sh.upload.model.web.BiliPreUploadRespose;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.StringSubstitutor;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.mime.FormBodyPartBuilder;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.entity.mime.content.StringBody;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;

import java.io.*;
import java.net.URI;
import java.util.Map;

/**
 * @author caiWen
 * @date 2023/2/19 14:48
 */
@Slf4j
public class UploadTest {
    public static Map<String, String> BILI_HEADERS = Maps.newHashMap();
    private static final String BILI_PRE_URL
            = "https://member.bilibili.com/preupload?access_key=${accessToken}&mid=${mid}&profile=ugcfr%2Fpc3";

    static {
        BILI_HEADERS.put("Connection", "keep-alive");
                BILI_HEADERS.put("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        BILI_HEADERS.put("User-Agent", "");
        BILI_HEADERS.put("Accept-Encoding", "gzip,deflate");
    }

    public static void main(String[] args) throws Exception {
        String filePath = "C:\\Users\\caiwe\\Videos\\2part-000_bilibili.mp4";
        File videoFile = new File(filePath);

        String accessToken = "4faa3abfb2c76c48c5adad38260c5e22";
        String mid = "3493088808930053";

        Map<String, String> urlParams = ImmutableMap.of(
                "accessToken", accessToken,
                "mid", mid
        );
        StringSubstitutor sub = new StringSubstitutor(urlParams);
        String preUrl = sub.replace(BILI_PRE_URL);

        String resp = HttpUtil.get(preUrl);
        BiliPreUploadRespose biliPreUploadRespose = JSON.parseObject(resp, BiliPreUploadRespose.class);

        String uploadUrl = biliPreUploadRespose.getUrl();
        String completeUploadUrl = biliPreUploadRespose.getComplete();
        String serverFileName = biliPreUploadRespose.getFilename();

        String[] params = StringUtils.split(uploadUrl, "?")[1].split("\\u0026");
        Map<String, String> paramMap = Maps.newHashMap();
        for (String param : params) {
            String[] split = param.split("=");
            paramMap.put(split[0], split[1]);
        }

        String videoName = videoFile.getName();
        long fileSize = FileUtil.size(videoFile);

        // 1.获得预加载上传的b站视频地址信息
        Integer chunkSize = 2 * 1024 * 1024;
        int partCount = (int) Math.ceil(fileSize * 1.0 / chunkSize);
        for (int i = 0; i < partCount; i++) {
            //当前分段起始位置
            long curChunkStart = i * chunkSize;
            // 当前分段大小  如果为最后一个大小为fileSize-curChunkStart  其他为partSize
            int curChunkSize = (i + 1 == partCount) ? (int) (fileSize - curChunkStart) : chunkSize;
            long curChunkEnd = curChunkStart + curChunkSize;

            byte[] bytes = VideoFileUtils.fetchBlock(videoFile, curChunkStart,  curChunkSize);

            String md5Str = DigestUtils.md5Hex(bytes);

//            BILI_HEADERS.put("Cookie", "PHPSESSID=" + serverFileName);
            HttpEntity requestEntity = MultipartEntityBuilder.create()
                    .addPart(FormBodyPartBuilder.create()
                            .setName("version")
                            .setBody(new StringBody("2.3.0.1088", ContentType.APPLICATION_FORM_URLENCODED))
                            .build())
                    .addPart(FormBodyPartBuilder.create()
                            .setName("filesize")
                            .setBody(new StringBody(String.valueOf(curChunkSize),
                                    ContentType.APPLICATION_FORM_URLENCODED))
                            .build())
                    .addPart(FormBodyPartBuilder.create()
                            .setName("chunk")
                            .setBody(new StringBody(String.valueOf(i), ContentType.APPLICATION_FORM_URLENCODED))
                            .build())
                    .addPart(FormBodyPartBuilder.create()
                            .setName("chunks")
                            .setBody(new StringBody(String.valueOf(partCount), ContentType.APPLICATION_FORM_URLENCODED))
                            .build())
                    .addPart(FormBodyPartBuilder.create()
                            .setName("md5")
                            .setBody(new StringBody(md5Str, ContentType.APPLICATION_FORM_URLENCODED))
                            .build())
                    .addPart(FormBodyPartBuilder.create()
                            .setName("file")
                            .setBody(new BlockStreamBody(curChunkStart, curChunkSize, videoFile))
                            .build())
                    .build();

            String respStr = HttpClientUtil.sendPost(uploadUrl, null, requestEntity);
            log.info("__________________________" + "respStr: {}" + respStr + "__________________________");
        }

        Map<String, String> paramMap2 = Maps.newHashMap();
        paramMap2.put("chunks", String.valueOf(3));
        paramMap2.put("filesize", String.valueOf(fileSize));
        paramMap2.put("md5", DigestUtils.md5Hex(new FileInputStream(filePath)));
        paramMap2.put("name", videoFile.getName());
        paramMap2.put("version", "2.3.0.1088");
        HttpEntity requestEntity2 = MultipartEntityBuilder.create()
                .addPart(FormBodyPartBuilder.create()
                        .setName("version")
                        .setBody(new StringBody("2.3.0.1088", ContentType.APPLICATION_FORM_URLENCODED))
                        .build())
                .addPart(FormBodyPartBuilder.create()
                        .setName("filesize")
                        .setBody(new StringBody(String.valueOf(fileSize), ContentType.APPLICATION_FORM_URLENCODED))
                        .build())
                .addPart(FormBodyPartBuilder.create()
                        .setName("chunks")
                        .setBody(new StringBody(String.valueOf(partCount), ContentType.APPLICATION_FORM_URLENCODED))
                        .build())
                .addPart(FormBodyPartBuilder.create()
                        .setName("md5")
                        .setBody(new StringBody(DigestUtils.md5Hex(new FileInputStream(filePath)), ContentType.APPLICATION_FORM_URLENCODED))
                        .build())
                .addPart(FormBodyPartBuilder.create()
                        .setName("name")
                        .setBody(new StringBody(videoName, ContentType.APPLICATION_FORM_URLENCODED))
                        .build())
                .build();

        String resp2 = HttpClientUtil.sendPost(completeUploadUrl, null, requestEntity2);
        log.info("__________________________" + "respStr2: {}, serverFileName: {}, partCount: {}.-------------------",
                resp2, serverFileName, partCount);
    }

    public static void main1(String[] args) throws IOException {
        String filePath = "C:\\Users\\caiwe\\Videos\\2part-000_bilibili.mp4";
        File videoFile = new File(filePath);
        long fileSize = FileUtil.size(videoFile);


    }
}
