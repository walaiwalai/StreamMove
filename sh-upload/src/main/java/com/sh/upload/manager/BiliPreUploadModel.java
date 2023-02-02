package com.sh.upload.manager;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.Maps;
import com.sh.config.utils.HttpClientUtil;
import com.sh.upload.model.BiliPreUploadInfoModel;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;


@Setter
@Getter
@Slf4j
public class BiliPreUploadModel {
    private String fileName;
    private BiliPreUploadInfoModel biliPreUploadInfo;
    private long size;
    private String cookies;
    private Map<String, Object> json;
    private String uploadUrl;
    private String uploadId;

    /**
     * 预上传是否成功
     */
    private boolean flag;


    public BiliPreUploadModel(String name, long size, String cookies) {
        this.fileName = name;
        this.size = size;
        this.cookies = cookies;
        String preUploadInfoQueryUrl = "https://member.bilibili.com/preupload?name=" + name + "&size=" + size
                + "&r=upos&profile=ugcupos%2Fbup&ssl=0&version=2.7.1&build=2070100&os=upos&upcdn=ws";
        this.biliPreUploadInfo = fetchPreUploadInfo(preUploadInfoQueryUrl);

        if ("1".equals(biliPreUploadInfo.getOK() + "")) {
            this.uploadUrl = "https:" + biliPreUploadInfo.getEndpoint() + biliPreUploadInfo.getUpos_uri().split(
                    "upos:/")[1];
            fetchUploadId();
            this.flag = true;
        } else {
            this.flag = false;
        }
    }

    public BiliPreUploadInfoModel fetchPreUploadInfo(String preUploadUrl) {
        Map<String, String> headers = new HashMap();
        headers.put("Accept", "*/*");
        headers.put("Accept-Encoding", "gzip, deflate, br");
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
        String resp = HttpClientUtil.sendPost(this.uploadUrl + "?uploads&output=json", headers, Maps.newHashMap());
        JSONObject resObj = JSON.parseObject(resp);
        this.uploadId = resObj.getString("upload_id");
    }


    private Map<String, String> buildHeaders() {
        Map<String, String> headers = new HashMap();
        headers.put("Accept", "*/*");
        headers.put("Accept-Encoding", "gzip, deflate, br");
        headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; WOW64) Chrome/63.0.3239.132 Safari/537.36");
        headers.put("Cookie", this.cookies);
        headers.put("Origin", "https://member.bilibili.com");
        headers.put("Referer", "https://member.bilibili.com/video/upload.html");
        headers.put("X-Upos-Auth", this.biliPreUploadInfo.getAuth());
        return headers;
    }
}
