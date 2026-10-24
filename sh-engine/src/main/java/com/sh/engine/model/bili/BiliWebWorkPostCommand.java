package com.sh.engine.model.bili;

import cn.hutool.core.io.FileUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.Lists;
import com.sh.config.exception.ErrorEnum;
import com.sh.config.exception.StreamerRecordException;
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
    private final String cookies;

    public BiliWebWorkPostCommand(List<RemoteSeverVideo> remoteSeverVideos, String recordPath, String cookies) {
        this.remoteSeverVideos = remoteSeverVideos;
        this.recordPath = recordPath;
        this.cookies = cookies;
    }

    /**
     * 提交稿件并返回稿件 ID，供后续加入主播合集使用。
     *
     * @return 投稿成功的 aid；失败时返回 null
     */
    public Long postWork() {
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
                .addHeader("cookie", cookies)
                .post(RequestBody.create(mediaType, JSON.toJSONString(buildPostWorkParamOnWeb())))
                .build();

        for (int i = 0; i < CHUNK_RETRY; i++) {
            try {
                if (i > 0) {
                    Thread.sleep(CHUNK_RETRY_DELAY);
                }
                String resp = OkHttpClientUtil.execute(request);
                JSONObject response = JSON.parseObject(resp);
                String code = response.getString("code");
                if (Objects.equals(code, "0")) {
                    JSONObject data = response.getJSONObject("data");
                    Long aid = data == null ? null : data.getLong("aid");
                    if (aid != null && aid > 0) {
                        log.info("postWork success, aid: {}, recordPath: {}", aid, this.recordPath);
                        return aid;
                    }
                    log.error("postWork succeeded without aid, recordPath: {}", this.recordPath);
                } else {
                    log.error("Bilibili post failed, code: {}, recordPath: {}", code, this.recordPath);
                }
                return null;
            } catch (Exception e) {
                log.error("postWork error, retry: {}/{}", i + 1, CHUNK_RETRY, e);
            }
        }

        return null;
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
        params.put("copyright", workMetaData.getCopyright());
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

        return uploadCover("data:image/jpeg;base64," + base64Content, cookies, ErrorEnum.POST_WORK_ERROR);
    }

    /**
     * 将图片 data URL 上传到 B 站，返回 B 站托管的封面地址。
     */
    static String uploadCover(String imageDataUrl, String cookies, ErrorEnum error) {
        String csrf = StringUtils.substringBetween(cookies + ";", "bili_jct=", ";");
        if (StringUtils.isBlank(csrf)) {
            throw new StreamerRecordException(error, "Missing Bilibili CSRF token for cover upload");
        }
        RequestBody requestBody = new FormBody.Builder()
                .add("csrf", csrf)
                .add("cover", imageDataUrl)
                .build();

        Request request = new Request.Builder()
                .url("https://member.bilibili.com/x/vu/web/cover/up")
                .post(requestBody)
                .addHeader("cookie", cookies)
                .build();
        JSONObject response;
        try {
            response = JSONObject.parseObject(OkHttpClientUtil.execute(request));
        } catch (RuntimeException e) {
            throw new StreamerRecordException(error, "Bilibili cover upload request failed", e);
        }
        JSONObject data = response == null ? null : response.getJSONObject("data");
        String url = data == null ? null : data.getString("url");
        if (response == null || response.getInteger("code") == null
                || response.getIntValue("code") != 0 || StringUtils.isBlank(url)) {
            throw new StreamerRecordException(error,
                    "Bilibili cover upload returned no URL, code=" + (response == null ? "null" : response.getString("code")));
        }
        return url;
    }


    private String fetchCsrf() {
        return StringUtils.substringBetween(cookies, "bili_jct=", ";");
    }

}
