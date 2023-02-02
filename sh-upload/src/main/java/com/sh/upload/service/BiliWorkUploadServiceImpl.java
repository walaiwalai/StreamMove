package com.sh.upload.service;

import cn.hutool.core.util.URLUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.sh.config.manager.ConfigManager;
import com.sh.config.model.config.StreamerInfo;
import com.sh.config.model.video.RemoteSeverVideo;
import com.sh.config.utils.HttpClientUtil;
import com.sh.upload.constant.UploadConstant;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.entity.InputStreamEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.*;
import java.util.concurrent.CountDownLatch;

import static com.sh.upload.constant.UploadConstant.*;

/**
 * @author caiWen
 * @date 2023/1/28 20:00
 */
@Component
@Slf4j
public class BiliWorkUploadServiceImpl implements PlatformWorkUploadService {
    @Resource
    ConfigManager configManager;

    /**
     * chunk上传失败重试次数
     */
    public static final Integer CHUNK_RETRY = 10;
    public static final Integer CHUNK_RETRY_DELAY = 3;

    @Override
    public boolean uploadChunk(InputStreamEntity uploadChunk, Integer chunkNo, Integer totalChunks,
            Long curChunkSize, Long curChunkStart, Long curChunkEnd, Long totalSize, CountDownLatch countDownLatch,
            Map<String, String> extension) {
        long startTime = System.currentTimeMillis();
        String chunkUploadUrl = String.format(UploadConstant.BILI_VIDEO_CHUNK_UPLOAD_URL,
                extension.get("uploadUrl"),
                chunkNo + 1,
                extension.get("uploadId"),
                totalChunks,
                chunkNo,
                curChunkSize,
                curChunkStart,
                curChunkEnd,
                totalSize
        );

        CloseableHttpClient client = HttpClientUtil.getClient();
        HttpPut httpPut = new HttpPut(chunkUploadUrl);
        httpPut.setEntity(uploadChunk);
        Map<String, String> uploadChunkHeaders = buildHeaders(extension);
        for (String headerName : uploadChunkHeaders.keySet()) {
            httpPut.addHeader(headerName, uploadChunkHeaders.get(headerName));
        }

        try {
            for (int i = 0; i < CHUNK_RETRY; i++) {
                CloseableHttpResponse response = client.execute(httpPut);
                String ret = EntityUtils.toString(response.getEntity(), "utf-8");
                if (Objects.equals(response.getStatusLine().getStatusCode(), 200)) {
                    log.info("chunk upload success, progress: {}/{}, time cost: {}s.", chunkNo, totalChunks,
                            (System.currentTimeMillis() - startTime) / 1000);
                    return true;
                } else {
                    log.error("the {}th chunk upload fail, ret: {}", chunkNo, ret);
                    Thread.sleep(CHUNK_RETRY_DELAY * 1000);
                }
            }
            return false;
        } catch (Exception e) {
            log.error("upload chunk error, uploadUrl: {}", chunkUploadUrl, e);
            return false;
        } finally {
            // 无论成功还是失败，都进行countDown操作
            countDownLatch.countDown();
        }
    }


    @Override
    public boolean finishChunksUpload(String videoName, Integer totalChunks, Map<String, String> extension) {
        List<Map<String, String>> parts = Lists.newArrayList();
        for (int i = 0; i < totalChunks; i++) {
            HashMap map = new HashMap();
            map.put("partNumber", i + 1);
            map.put("eTag", "etag");
            parts.add(map);
        }
        JSONObject params = new JSONObject();
        params.put("parts", parts);

        String chunkUploadFinishUrl = String.format(UploadConstant.BILI_CHUNK_UPLOAD_FINISH_URL,
                extension.get(UploadConstant.BILI_UPLOAD_URL),
                URLUtil.encode(videoName),
                extension.get(UploadConstant.BILI_UPLOAD_ID),
                extension.get(UploadConstant.BILI_BIZ_ID)
        );

        Map<String, String> headers = buildHeaders(extension);
        String resp = HttpClientUtil.sendPost(chunkUploadFinishUrl, headers, params);
        JSONObject resObj = JSON.parseObject(resp);
        if (Objects.equals(resObj.getString("OK"), "1")) {
            log.info("finish chunks upload success");
            return true;
        } else {
            log.error("finish chunks upload fail, res: {}", resp);
            return false;
        }
    }

    @Override
    public boolean postWork(String streamerName, List<RemoteSeverVideo> remoteSeverVideos,
            Map<String, String> extension) {
        List<StreamerInfo> streamerInfos = configManager.getConfig().getStreamerInfos();
        StreamerInfo streamerInfo = streamerInfos.stream()
                .filter(info -> StringUtils.equals(info.getName(), streamerName))
                .findFirst().orElse(null);
        if (streamerInfo == null) {
            log.error("has no streamer info to post work, streamerName: {}", streamerName);
            return false;
        }

        String postWorkUrl = String.format(UploadConstant.BILI_POST_WORK, System.currentTimeMillis(),
                fetchCsrf(configManager.getConfig().getPersonInfo().getBiliCookies()));
        Map<String, String> headers = buildHeaders(extension);
        String resp = HttpClientUtil.sendPost(postWorkUrl, headers,
                buildPostWorkParam(streamerInfo, remoteSeverVideos, extension));
        JSONObject respObj = JSONObject.parseObject(resp);
        if (Objects.equals(respObj.getString("code"), 0)) {
            log.info("postWork success, video is upload, title: {}", extension.get(BILI_VIDEO_TILE));
            return true;
        } else {
            log.error("postWork failed, res: {}, title: {}", resp, extension.get(BILI_VIDEO_TILE));
            return false;
        }
    }


    private Map<String, String> buildHeaders(Map<String, String> extension) {
        Map<String, String> uploadChunkHeaders = Maps.newHashMap();
        uploadChunkHeaders.put("Accept", "*/*");
        uploadChunkHeaders.put("Accept-Encoding", "gzip, deflate, br");
        uploadChunkHeaders.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; WOW64) Chrome/63.0.3239.132 Safari/537.36");
        uploadChunkHeaders.put("Origin", "https://member.bilibili.com");
        uploadChunkHeaders.put("Referer", "https://member.bilibili.com/video/upload.html");
        uploadChunkHeaders.put("Cookie", configManager.getConfig().getPersonInfo().getBiliCookies());
        uploadChunkHeaders.put("X-Upos-Auth", extension.get(UploadConstant.BILI_UPOS_URI));
        return uploadChunkHeaders;
    }

    private String fetchCsrf(String biliCookies) {
        return StringUtils.substringBetween(biliCookies, "bili_jct=", ";");
    }

    private JSONObject buildPostWorkParam(StreamerInfo streamerInfo, List<RemoteSeverVideo> remoteSeverVideos,
            Map<String, String> extension) {
        JSONObject params = new JSONObject();
        params.put("cover", streamerInfo.getCover());
        params.put("title", extension.get(BILI_VIDEO_TILE));
        params.put("tid", streamerInfo.getTid());
        params.put("tag", StringUtils.join(streamerInfo.getTags(), ","));
        params.put("desc", extension.get(BILI_VIDEO_DESC));
        params.put("dynamic", extension.get(BILI_VIDEO_DYNAMIC));
        params.put("copyright", streamerInfo.getCopyright());
        params.put("videos", remoteSeverVideos);
        params.put("no_reprint", 0);

        return params;

    }
}
