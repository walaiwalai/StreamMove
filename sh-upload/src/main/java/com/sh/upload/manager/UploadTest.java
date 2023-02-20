package com.sh.upload.manager;

import cn.hutool.core.io.FileUtil;
import cn.hutool.crypto.digest.MD5;
import cn.hutool.http.HttpUtil;
import com.alibaba.fastjson.JSON;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.sh.config.model.video.FailUploadVideoChunk;
import com.sh.config.utils.HttpClientUtil;
import com.sh.upload.model.BiliPreUploadInfoModel;
import com.sh.upload.model.web.BiliPreUploadRespose;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.StringSubstitutor;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.InputStreamEntity;
import org.apache.http.entity.mime.FormBodyPartBuilder;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.entity.mime.content.FileBody;
import org.apache.http.entity.mime.content.InputStreamBody;
import org.apache.http.entity.mime.content.StringBody;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.message.BasicHeader;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * @author caiWen
 * @date 2023/2/19 14:48
 */
public class UploadTest {
    public static Map<String, String> BILI_HEADERS = Maps.newHashMap();
    private static final String BILI_PRE_URL
            = "https://member.bilibili.com/preupload?access_key=${accessToken}&mid=${mid}&profile=ugcfr%2Fpc3";

    static {
        BILI_HEADERS.put("Connection", "alive");
        BILI_HEADERS.put("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        BILI_HEADERS.put("User-Agent",
                "Mozilla/5.0 (Linux; Android 6.0; Nexus 5 Build/MRA58N) AppleWebKit/537.36 (KHTML, like Gecko) "
                        + "Chrome/109.0.0.0 Mobile Safari/537.36 Edg/109.0.1518.55");
        BILI_HEADERS.put("Accept-Encoding", "gzip,deflate");
    }

    public static void main(String[] args) throws Exception {
        String filePath = "D:\\360MoveData\\Users\\caiwe\\Desktop\\test-2023-02-12-part-001.mp4";
        File videoFile = new File(filePath);

        String accessToken = "73e93b10ad24b367db3d15e1aa96b012";
        String mid = "14527951";

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
        Integer chunkSize = 10 * 1024 * 1024;
        int partCount = (int) Math.ceil(fileSize * 1.0 / chunkSize);

        for (int i = 0; i < partCount; i++) {
            long uploadStartTime = System.currentTimeMillis();
            //当前分段起始位置
            long curChunkStart = i * chunkSize;
            // 当前分段大小  如果为最后一个大小为fileSize-curChunkStart  其他为partSize
            long curChunkSize = (i + 1 == partCount) ? (fileSize - curChunkStart) : chunkSize;
            long curChunkEnd = curChunkStart + curChunkSize;

            FileInputStream fis = new FileInputStream(videoFile);
            fis.skip(curChunkStart);

            InputStreamEntity chunkData = new InputStreamEntity(fis, curChunkSize);

            MD5 md5 = MD5.create();
            String md5Str = md5.digestHex(chunkData.getContent());

            Map<String, String> headers = Maps.newHashMap();
            BILI_HEADERS.put("Cookie", "PHPSESSID=" + serverFileName);


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
                            .setBody(new StringBody(String.valueOf(i+1), ContentType.APPLICATION_FORM_URLENCODED))
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
//                            .setBody(new FileBody(chunkData.getContent(), ContentType.APPLICATION_OCTET_STREAM))
                            .setBody(new FileBody(videoFile, ContentType.APPLICATION_OCTET_STREAM))
                            .addField("filename", "2part-000_bilibili.mp4")
                            .build())
                    .build();

            String respStr = HttpClientUtil.sendPost(uploadUrl, BILI_HEADERS, requestEntity);
            System.out.println(respStr);
        }
    }
}
