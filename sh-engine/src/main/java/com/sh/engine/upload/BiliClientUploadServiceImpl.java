package com.sh.engine.upload;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.Maps;
import com.sh.config.manager.ConfigFetcher;
import com.sh.config.model.config.StreamerInfo;
import com.sh.config.model.video.LocalVideo;
import com.sh.config.model.video.RemoteSeverVideo;
import com.sh.config.utils.HttpClientUtil;
import com.sh.config.utils.VideoFileUtils;
import com.sh.engine.model.bili.body.BlockStreamBody;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.mime.FormBodyPartBuilder;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.entity.mime.content.StringBody;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.sh.engine.constant.UploadConstant.BILI_UPOS_AUTH;
import static com.sh.engine.constant.UploadConstant.BILI_VIDEO_TILE;

/**
 * @Author caiwen
 * @Date 2023 12 21 22 47
 **/
@Service("biliClient")
@Slf4j
public class BiliClientUploadServiceImpl implements PlatformWorkUploadService{
    private static final String CLIENT_POST_VIDEO_URL
            = "https://member.bilibili.com/x/vu/client/add?access_key=%s";
    private static final Map<String, String> CLIENT_HEADERS = Maps.newHashMap();

    static {
        CLIENT_HEADERS.put("Connection", "keep-alive");
        CLIENT_HEADERS.put("Content-Type", "application/json");
        CLIENT_HEADERS.put("user-agent", "");
    }

    /**
     * chunk上传失败重试次数
     */
    public static final Integer CHUNK_RETRY = 3;
    public static final Integer CHUNK_RETRY_DELAY = 1000;

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
                        .setBody(new BlockStreamBody(curChunkStart, curChunkSize, targetFile))
                        .build())
                .build();

        for (int i = 0; i < CHUNK_RETRY; i++) {
            try {
                Thread.sleep(CHUNK_RETRY_DELAY);
                String respStr = HttpClientUtil.sendPost(uploadUrl, null, requestEntity);
                JSONObject respObj = JSONObject.parseObject(respStr);
                if (Objects.equals(respObj.getString("info"), "Successful.")) {
                    log.info("chunk upload success, progress: {}/{}, time cost: {}s.", chunkNo, totalChunks,
                            (System.currentTimeMillis() - startTime) / 1000);
                    return true;
                } else {
                    log.error("{}th chunk upload fail, ret: {}, will retry...", chunkNo, respStr);
                }
            } catch (Exception e) {
                log.error("{}th chunk upload error, will retry...", chunkNo, e);
            }
        }
        return false;
    }

    @Override
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
        String completeResp = HttpClientUtil.sendPost(finishUrl, null, completeEntity);
        JSONObject respObj = JSONObject.parseObject(completeResp);
        if (Objects.equals(respObj.getString("OK"), "1")) {
            log.info("complete upload success, videoName: {}", videoName);
            return true;
        } else {
            log.error("complete upload fail, videoName: {}", videoName);
            return false;
        }
    }

    @Override
    public boolean postWork(String streamerName, List<RemoteSeverVideo> remoteSeverVideos,
                            Map<String, String> extension) {
        if (CollectionUtils.isEmpty(remoteSeverVideos)) {
            return false;
        }

        StreamerInfo streamerInfo = ConfigFetcher.getStreamerInfoByName(streamerName);
        if (streamerInfo == null) {
            log.error("has no streamer info to post work, streamerName: {}", streamerName);
            return false;
        }

        String accessToken = ConfigFetcher.getInitConfig().getAccessToken();
        String postWorkUrl = String.format(CLIENT_POST_VIDEO_URL, accessToken);
        String resp = HttpClientUtil.sendPost(postWorkUrl, CLIENT_HEADERS,
                buildPostWorkParamOnClient(streamerInfo, remoteSeverVideos, extension));
        JSONObject respObj = JSONObject.parseObject(resp);
        if (Objects.equals(respObj.getString("code"), "0")) {
            log.info("postWork success, video is uploaded, remoteSeverVideos: {}",
                    JSON.toJSONString(remoteSeverVideos));
            return true;
        } else {
            log.error("postWork failed, res: {}, title: {}", resp, JSON.toJSONString(remoteSeverVideos));
            return false;
        }
    }

    private JSONObject buildPostWorkParamOnClient(StreamerInfo streamerInfo, List<RemoteSeverVideo> remoteSeverVideos,
                                                  Map<String, String> extension) {
        JSONObject params = new JSONObject();
        params.put("cover", streamerInfo.getCover());
        params.put("build", 1088);
        params.put("title", extension.get(BILI_VIDEO_TILE));
        params.put("tid", streamerInfo.getTid());
        params.put("tag", StringUtils.join(streamerInfo.getTags(), ","));
        params.put("desc", streamerInfo.getDesc());
        params.put("dynamic", streamerInfo.getDynamic());
        params.put("copyright", 1);
        params.put("source", streamerInfo.getSource());
        params.put("videos", remoteSeverVideos);
        params.put("no_reprint", 0);
        params.put("open_elec", 1);

        return params;
    }
}
