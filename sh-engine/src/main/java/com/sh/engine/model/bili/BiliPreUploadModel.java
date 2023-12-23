package com.sh.engine.model.bili;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.Maps;
import com.sh.config.utils.HttpClientUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.HashMap;
import java.util.Map;


@Slf4j
public class BiliPreUploadModel {
    private String fileName;
    private long size;
    private String cookies;


    private BiliPreUploadInfoModel biliPreUploadVideoInfo;
    private String uploadUrl;
    private String uploadId;


    public BiliPreUploadInfoModel getBiliPreUploadVideoInfo() {
        return biliPreUploadVideoInfo;
    }

    public String getUploadUrl() {
        return uploadUrl;
    }

    public String getUploadId() {
        return uploadId;
    }


    public BiliPreUploadModel(String name, long size, String cookies) {
        this.fileName = name;
        this.size = size;
        this.cookies = cookies;
                String preUploadVideoInfoUrl = "https://member.bilibili.com/preupload?name=" + name + "&size=" + size
                + "&r=upos&profile=ugcupos%2Fbup&ssl=0&version=2.7.1&build=2070100&os=upos&upcdn=ws";
        this.biliPreUploadVideoInfo = fetchPreUploadInfo(preUploadVideoInfoUrl);

        if (StringUtils.equals(biliPreUploadVideoInfo.getOk() + "", "1")) {
            this.uploadUrl = "https:" + biliPreUploadVideoInfo.getEndpoint() + biliPreUploadVideoInfo.getUposUri().split(
                    "upos:/")[1];
            fetchUploadId();
        }
    }

    public BiliPreUploadInfoModel fetchPreUploadInfo(String preUploadUrl) {
        Map<String, String> headers = new HashMap();
        headers.put("Accept", "*/*");
        headers.put("Accept-Encoding", "deflate");
        headers.put("Cookie", cookies);
        headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; WOW64) Chrome/63.0.3239.132 Safari/537.36");
        String res = HttpClientUtil.sendGet(preUploadUrl, headers, null);

        return JSON.parseObject(res, BiliPreUploadInfoModel.class);
    }

    /**
     * 获取视频上传的id
     */
    public void fetchUploadId() {
        Map<String, String> headers = buildHeaders();
        String fetchUploadIdUrl = String.format("%s?uploads&output=json&", this.uploadUrl);
        String resp = HttpClientUtil.sendPost(fetchUploadIdUrl, headers, Maps.newHashMap());
        JSONObject resObj = JSON.parseObject(resp);
        this.uploadId = resObj.getString("upload_id");
    }


    private Map<String, String> buildHeaders() {
        Map<String, String> headers = new HashMap();
        headers.put("Accept", "*/*");
        headers.put("Accept-Encoding", "deflate");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8,en-GB;q=0.7,en-US;q=0.6,ja;q=0.5");
        headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; WOW64) Chrome/63.0.3239.132 Safari/537.36");
        headers.put("Origin", "https://member.bilibili.com");
        headers.put("Referer", "https://member.bilibili.com/platform/upload/video/frame");
        headers.put("X-Upos-Auth", this.biliPreUploadVideoInfo.getAuth());
        return headers;
    }
}
