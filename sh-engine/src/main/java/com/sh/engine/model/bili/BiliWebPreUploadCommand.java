package com.sh.engine.model.bili;

import cn.hutool.core.io.FileUtil;
import com.alibaba.fastjson.JSON;
import com.sh.config.exception.ErrorEnum;
import com.sh.config.exception.StreamerRecordException;
import com.sh.config.manager.ConfigFetcher;
import com.sh.config.utils.OkHttpClientUtil;
import com.sh.engine.constant.RecordConstant;
import com.sh.engine.model.bili.web.BiliClientPreUploadParams;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;

import java.io.File;
import java.io.IOException;


@Slf4j
public class BiliWebPreUploadCommand {
    private String fileName;
    private long size;


    private BiliWebPreUploadParams biliWebPreUploadParams;
    private BiliClientPreUploadParams biliClientPreUploadParams;

    public BiliWebPreUploadParams getBiliWebPreUploadParams() {
        return biliWebPreUploadParams;
    }

    public BiliClientPreUploadParams getBiliClientPreUploadParams() {
        return biliClientPreUploadParams;
    }


    public BiliWebPreUploadCommand(File videoFile) {
        this.fileName = videoFile.getName();
        this.size = FileUtil.size(videoFile);
    }

    public void doWebPreUp() {
        this.biliWebPreUploadParams = fetchPreUploadInfo();
        if (biliWebPreUploadParams.getOk() == 1) {
            String upUrl = "https:" + biliWebPreUploadParams.getEndpoint()
                    + biliWebPreUploadParams.getUposUri().split("upos:/")[1];
            biliWebPreUploadParams.setUploadUrl(upUrl);
            String upId = fetchUploadId();
            biliWebPreUploadParams.setUploadId(upId);
        } else {
            throw new StreamerRecordException(ErrorEnum.PRE_UPLOAD_ERROR);
        }
    }

    public void doClientPreUp() {
        String preUrl = RecordConstant.BILI_CLIENT_PRE_URL
                .replace("{accessToken}", ConfigFetcher.getInitConfig().getAccessToken())
                .replace("{mid}", ConfigFetcher.getInitConfig().getMid().toString());
        OkHttpClient CLIENT = new OkHttpClient();
        Request request = new Request.Builder()
                .url(preUrl)
                .build();
        BiliClientPreUploadParams resp;
        try (Response response = CLIENT.newCall(request).execute()) {
            if (response.isSuccessful()) {
                resp = JSON.parseObject(response.body().string(), BiliClientPreUploadParams.class);
            } else {
                String message = response.message();
                String bodyStr = response.body() != null ? response.body().string() : null;
                log.error("pre up video failed, message: {}, bodyStr: {}", message, bodyStr);
                throw new StreamerRecordException(ErrorEnum.PRE_UPLOAD_ERROR);
            }
        } catch (IOException e) {
            log.error("pre up video error", e);
            throw new StreamerRecordException(ErrorEnum.PRE_UPLOAD_ERROR);
        }

        if (resp.getOK() == 1) {
            this.biliClientPreUploadParams = resp;
        } else {
            throw new StreamerRecordException(ErrorEnum.PRE_UPLOAD_ERROR);
        }
    }

    private BiliWebPreUploadParams fetchPreUploadInfo() {
        String preUploadUrl = RecordConstant.BILI_WEB_PRE_UPLOAD_URL
                .replace("{name}", fileName)
                .replace("{size}", String.valueOf(size));
        Request request = new Request.Builder()
                .url(preUploadUrl)
                .addHeader("Accept", "*/*")
                .addHeader("Accept-Encoding", "deflate")
                .addHeader("Cookie", ConfigFetcher.getInitConfig().getBiliCookies())
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; WOW64) Chrome/63.0.3239.132 Safari/537.36")
                .build();
        String resp = OkHttpClientUtil.execute(request);
        return JSON.parseObject(resp, BiliWebPreUploadParams.class);
    }

    /**
     * 获取视频上传的id
     */
    private String fetchUploadId() {
        String url = this.biliWebPreUploadParams.getUploadUrl() + "?uploads&output=json&biz_id=" + this.biliWebPreUploadParams.getBizId() + "&filesize=" + size +
                "&profile=ugcfx%2Fbup&partsize=" + this.biliWebPreUploadParams.getChunkSize();
        Request request = new Request.Builder()
                .url(url)
                .addHeader("Accept", "*/*")
                .addHeader("Accept-Encoding", "deflate")
                .addHeader("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8,en-GB;q=0.7,en-US;q=0.6,ja;q=0.5")
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; WOW64) Chrome/63.0.3239.132 Safari/537.36")
                .addHeader("Origin", "https://member.bilibili.com")
                .addHeader("Referer", "https://member.bilibili.com/platform/upload/video/frame")
                .addHeader("X-Upos-Auth", this.biliWebPreUploadParams.getAuth())
                .post(RequestBody.create(MediaType.parse("application/json"), ""))
                .build();

        String resp = OkHttpClientUtil.execute(request);
        return JSON.parseObject(resp).getString("upload_id");
    }
}
