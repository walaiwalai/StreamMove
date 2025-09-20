package com.sh.engine.model.bili;

import cn.hutool.core.io.FileUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.Lists;
import com.sh.config.manager.ConfigFetcher;
import com.sh.config.model.config.StreamerConfig;
import com.sh.config.utils.OkHttpClientUtil;
import com.sh.config.utils.PictureFileUtil;
import com.sh.engine.constant.RecordConstant;
import com.sh.engine.model.StreamerInfoHolder;
import com.sh.engine.model.video.RemoteSeverVideo;
import com.sh.engine.processor.uploader.UploaderFactory;
import com.sh.engine.processor.uploader.meta.BiliWorkMetaData;
import lombok.extern.slf4j.Slf4j;
import okhttp3.FormBody;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.util.List;
import java.util.Objects;

/**
 * B站作评上传命令
 */
@Slf4j
public class BiliWebWorkPostCommand {
    private static final int CHUNK_RETRY = 10;
    private static final int CHUNK_RETRY_DELAY = 500;

    private List<RemoteSeverVideo> remoteSeverVideos;
    private String recordPath;

    public BiliWebWorkPostCommand(List<RemoteSeverVideo> remoteSeverVideos, String recordPath) {
        this.remoteSeverVideos = remoteSeverVideos;
        this.recordPath = recordPath;
    }

    public boolean postWork() {
        String postWorkUrl = RecordConstant.BILI_POST_WORK
                .replace("{t}", String.valueOf(System.currentTimeMillis()))
                .replace("{csrf}", fetchCsrf());

        MediaType mediaType = MediaType.parse("application/json");
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
                .addHeader("cookie", fetchBiliCookies())
                .post(RequestBody.create(mediaType, JSON.toJSONString(buildPostWorkParamOnWeb())))
                .build();

        for (int i = 0; i < CHUNK_RETRY; i++) {
            try {
                if (i > 0) {
                    Thread.sleep(CHUNK_RETRY_DELAY);
                }
                String resp = OkHttpClientUtil.execute(request);
                String code = JSON.parseObject(resp).getString("code");
                if (Objects.equals(code, "0")) {
                    log.info("postWork success, video is uploaded, recordPath: {}", this.recordPath);
                    return true;
                } else {
                    log.error("postWork failed, res: {}, title: {}, retry: {}/{}.", resp, JSON.toJSONString(this.remoteSeverVideos), i + 1, CHUNK_RETRY);
                }
                return true;
            } catch (Exception e) {
                log.error("postWork error, retry: {}/{}", i + 1, CHUNK_RETRY, e);
            }
        }

        return false;
    }

    private JSONObject buildPostWorkParamOnWeb() {
        String streamerName = StreamerInfoHolder.getCurStreamerName();
        StreamerConfig streamerConfig = ConfigFetcher.getStreamerInfoByName(streamerName);
        BiliWorkMetaData workMetaData = (BiliWorkMetaData) new UploaderFactory.BiliMetaDataBuilder()
                .buildMetaData(streamerConfig, this.recordPath);


        List<JSONObject> videoObjs = Lists.newArrayList();
        for (RemoteSeverVideo remoteSeverVideo : this.remoteSeverVideos) {
            JSONObject videoObj = new JSONObject();
            videoObj.put("title", FileUtil.getPrefix(remoteSeverVideo.getLocalFilePath()));
            videoObj.put("filename", remoteSeverVideo.getServerFileName());
            videoObjs.add(videoObj);
        }

        String thumbnailUrl = uploadThumbnail();

        JSONObject params = new JSONObject();
        params.put("cover", StringUtils.isBlank(thumbnailUrl) ? workMetaData.getCover() : thumbnailUrl);
        params.put("title", workMetaData.getTitle());
        params.put("tid", workMetaData.getTid());
        params.put("tag", StringUtils.join(workMetaData.getTags(), ","));
        params.put("desc", workMetaData.getDesc());
        params.put("dynamic", workMetaData.getDynamic());
        params.put("copyright", 1);
        params.put("source", workMetaData.getSource());
        params.put("videos", videoObjs);
        params.put("no_reprint", 0);
        params.put("open_elec", 1);
        params.put("csrf", fetchCsrf());

        return params;
    }

    /**
     * 上传视频封面
     */
    private String uploadThumbnail() {
        File file = new File(this.recordPath, RecordConstant.THUMBNAIL_FILE_NAME);
        if (!file.exists()) {
            return null;
        }
        String base64Content = PictureFileUtil.fileToBase64(file);
        if (StringUtils.isBlank(base64Content)) {
            return null;
        }

        String csrf = fetchCsrf();
        String sessData = fetchSessData();
        RequestBody requestBody = new FormBody.Builder()
                .add("csrf", csrf)
                .add("cover", "data:image/jpeg;base64," + base64Content)
                .build();

        Request request = new Request.Builder()
                .url("https://member.bilibili.com/x/vu/web/cover/up")
                .post(requestBody)
                .addHeader("cookie", "SESSDATA=" + sessData + "; bili_jct=" + csrf)
                .build();
        String resp = OkHttpClientUtil.execute(request);
        JSONObject respObj = JSONObject.parseObject(resp);
        return respObj.getJSONObject("data").getString("url");
    }


    /**
     * 获取b站cookies
     * @return b站cookies
     */
    private String fetchBiliCookies() {
        String streamerName = StreamerInfoHolder.getCurStreamerName();
        StreamerConfig streamerConfig = ConfigFetcher.getStreamerInfoByName(streamerName);
        return StringUtils.isNotBlank(streamerConfig.getCertainBiliCookies()) ? streamerConfig.getCertainBiliCookies() : ConfigFetcher.getInitConfig().getBiliCookies();
    }

    private String fetchCsrf() {
        return StringUtils.substringBetween(fetchBiliCookies(), "bili_jct=", ";");
    }

    private String fetchSessData() {
        return StringUtils.substringBetween(fetchBiliCookies(), "SESSDATA=", ";");
    }
}
