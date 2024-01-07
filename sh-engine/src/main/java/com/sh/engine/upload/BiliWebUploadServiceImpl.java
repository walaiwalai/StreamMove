package com.sh.engine.upload;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.Lists;
import com.sh.config.manager.ConfigFetcher;
import com.sh.config.model.config.StreamerInfo;
import com.sh.config.model.video.LocalVideo;
import com.sh.config.model.video.RemoteSeverVideo;
import com.sh.config.utils.VideoFileUtils;
import com.sh.engine.constant.RecordConstant;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.sh.engine.constant.RecordConstant.BILI_UPOS_AUTH;
import static com.sh.engine.constant.RecordConstant.BILI_VIDEO_TILE;

/**
 * @Author caiwen
 * @Date 2024 01 05 21 27
 **/
@Service("biliWeb")
@Slf4j
public class BiliWebUploadServiceImpl implements PlatformWorkUploadService {
    private static final OkHttpClient CLIENT = new OkHttpClient();
    public static final Integer CHUNK_RETRY = 3;
    public static final Integer CHUNK_RETRY_DELAY = 500;

    @Override
    public boolean uploadChunk(String uploadUrl, File targetFile, Integer chunkNo, Integer totalChunks, Integer curChunkSize, Long curChunkStart, Map<String, String> extension) {
        log.info("start to upload {}th video chunk, curChunkSize: {}M.", chunkNo + 1, curChunkSize / 1024 / 1024);
        long startTime = System.currentTimeMillis();

        byte[] bytes = null;
        try {
            bytes = VideoFileUtils.fetchBlock(targetFile, curChunkStart, curChunkSize);
        } catch (IOException e) {
            log.error("fetch chunk error", e);
        }

        RequestBody chunkRequestBody = RequestBody
                .create(MediaType.parse("application/octet-stream"), bytes);
        Request request = new Request.Builder()
                .url(uploadUrl)
                .addHeader("Accept", "*/*")
                .addHeader("Accept-Encoding", "deflate")
                .addHeader("content-type", "application/octet-stream")
                .addHeader("user-agent", "Mozilla/5.0 (Windows NT 10.0; WOW64) Chrome/63.0.3239.132 Safari/537.36")
                .addHeader("origin", "https://member.bilibili.com")
                .addHeader("referer", "https://member.bilibili.com/platform/upload/video/frame")
                .addHeader("sec-fetch-mode", "cors")
                .addHeader("sec-fetch-site", "cross-site")
                .addHeader("cookie", ConfigFetcher.getInitConfig().getBiliCookies())
                .addHeader("x-upos-auth", extension.get(BILI_UPOS_AUTH))
                .put(chunkRequestBody)
                .build();
        for (int i = 0; i < CHUNK_RETRY; i++) {
            try (Response response = CLIENT.newCall(request).execute()) {
                Thread.sleep(CHUNK_RETRY_DELAY);
                if (response.isSuccessful()) {
                    log.info("chunk upload success, progress: {}/{}, time cost: {}s.", chunkNo, totalChunks,
                            (System.currentTimeMillis() - startTime) / 1000);
                    return true;
                } else {
                    String message = response.message();
                    String bodyStr = response.body() != null ? response.body().string() : null;
                    log.error("the {}th chunk upload failed, will retry... message: {}, bodyStr: {}", chunkNo, message, bodyStr);
                }
            } catch (Exception e) {
                log.error("th {}th chunk upload error, will retry...", chunkNo, e);
            }
        }
        return false;
    }

    @Override
    public boolean finishChunks(String finishUrl, int totalChunks, String videoName, LocalVideo localVideo, Map<String, String> extension) throws Exception {
        List<Map<String, String>> parts = Lists.newArrayList();
        for (int i = 0; i < totalChunks; i++) {
            HashMap map = new HashMap();
            map.put("partNumber", i + 1);
            map.put("eTag", "etag");
            parts.add(map);
        }
        JSONObject params = new JSONObject();
        params.put("parts", parts);

        MediaType mediaType = MediaType.parse("application/json");
        RequestBody requestBody = RequestBody.create(mediaType, JSON.toJSONString(params));
        Request request = new Request.Builder()
                .url(finishUrl)
                .addHeader("Accept", "*/*")
                .addHeader("Accept-Encoding", "deflate")
                .addHeader("content-type", "application/octet-stream")
                .addHeader("user-agent", "Mozilla/5.0 (Windows NT 10.0; WOW64) Chrome/63.0.3239.132 Safari/537.36")
                .addHeader("origin", "https://member.bilibili.com")
                .addHeader("referer", "https://member.bilibili.com/platform/upload/video/frame")
                .addHeader("sec-fetch-mode", "cors")
                .addHeader("sec-fetch-site", "cross-site")
                .addHeader("cookie", ConfigFetcher.getInitConfig().getBiliCookies())
                .addHeader("x-upos-auth", extension.get(BILI_UPOS_AUTH))
                .post(requestBody)
                .build();

        try (Response response = CLIENT.newCall(request).execute()) {
            if (response.isSuccessful()) {
                String resp = response.body().string();
                String ok = JSON.parseObject(resp).getString("OK");
                return Objects.equals(ok, "1");
            } else {
                String message = response.message();
                String bodyStr = response.body() != null ? response.body().string() : null;
                log.error("finish chunks failed, message: {}, bodyStr: {}", message, bodyStr);
            }
        } catch (IOException e) {
            log.error("finish chunks error", e);
        }
        return false;
    }

    @Override
    public boolean postWork(String streamerName, List<RemoteSeverVideo> remoteSeverVideos, Map<String, String> extension) {
        StreamerInfo streamerInfo = ConfigFetcher.getStreamerInfoByName(streamerName);

        String postWorkUrl = RecordConstant.BILI_POST_WORK
                .replace("{t}", String.valueOf(System.currentTimeMillis()))
                .replace("{csrf}", fetchCsrf(ConfigFetcher.getInitConfig().getBiliCookies()));

        JSONObject params = buildPostWorkParam(streamerInfo, remoteSeverVideos, extension);
        MediaType mediaType = MediaType.parse("application/json");
        RequestBody requestBody = RequestBody.create(mediaType, JSON.toJSONString(params));

        Request request = new Request.Builder()
                .url(postWorkUrl)
                .addHeader("Accept", "*/*")
                .addHeader("Accept-Encoding", "deflate")
                .addHeader("content-type", "application/octet-stream")
                .addHeader("user-agent", "Mozilla/5.0 (Windows NT 10.0; WOW64) Chrome/63.0.3239.132 Safari/537.36")
                .addHeader("origin", "https://member.bilibili.com")
                .addHeader("referer", "https://member.bilibili.com/platform/upload/video/frame")
                .addHeader("sec-fetch-mode", "cors")
                .addHeader("sec-fetch-site", "cross-site")
                .addHeader("cookie", ConfigFetcher.getInitConfig().getBiliCookies())
                .addHeader("x-upos-auth", extension.get(BILI_UPOS_AUTH))
                .post(requestBody)
                .build();

        String title = extension.get(BILI_VIDEO_TILE);
        try (Response response = CLIENT.newCall(request).execute()) {
            if (response.isSuccessful()) {
                String resp = response.body().string();
                String code = JSON.parseObject(resp).getString("code");
                if (Objects.equals(code, "0")) {
                    log.info("postWork success, video is upload, title: {}", title);
                    return true;
                } else {
                    log.error("postWork failed, res: {}, title: {}", resp, title);
                    return false;
                }
            } else {
                String message = response.message();
                String bodyStr = response.body() != null ? response.body().string() : null;
                log.error("postWork failed, message: {}, bodyStr: {}", message, bodyStr);
            }
        } catch (IOException e) {
            log.error("finish chunks error", e);
        }
        return false;
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
        params.put("desc", streamerInfo.getDesc());
        params.put("dynamic", streamerInfo.getDynamic());
        params.put("copyright", 1);
        params.put("source", streamerInfo.getSource());
        params.put("videos", remoteSeverVideos);
        params.put("no_reprint", 0);
        params.put("open_elec", 0);
        params.put("csrf", fetchCsrf(ConfigFetcher.getInitConfig().getBiliCookies()));

        return params;
    }
}
