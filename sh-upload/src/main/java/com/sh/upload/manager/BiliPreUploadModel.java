package com.sh.upload.manager;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.Maps;
import com.sh.config.utils.HttpClientUtil;
import com.sh.upload.model.BiliPreUploadInfoModel;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.HashMap;
import java.util.Map;


@Setter
@Getter
@Slf4j
public class BiliPreUploadModel {
    private String fileName;
    private BiliPreUploadInfoModel biliPreUploadVideoInfo;
    private long size;
    private String cookies;
    private String uploadUrl;
    private String uploadId;


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

    public static void main(String[] args) {
//        https://member.bilibili.com/preupload?name=Xiaohu-2023-02-15-part-003.mp4&size=19716776&r=upos&profile=ugcupos%2Fbup&ssl=0&version=2.7.1&build=2070100&os=upos&upcdn=ws
        BiliPreUploadModel s = new BiliPreUploadModel("Xiaohu-2023-02-15-part-003.mp4", 19716776L,
                "_uuid=51082D299-A10F5-5C92-D327-48E1F8A2816932071infoc; "
                        + "buvid3=24635A34-84D2-3E1A-32C1-EFF41DB1FE7A31950infoc; b_nut=1675432032; "
                        + "buvid_fp_plain=undefined; b_lsid=4C877BDC_1865AAF8CD2; "
                        + "fingerprint=f4143708344460adbbb91eb07d6564d7; SESSDATA=f252a3fa,1692110753,a51ea*21; "
                        + "bili_jct=875c1da0faa8f770f673bd8576fe6f08; DedeUserID=3493088808930053; "
                        + "DedeUserID__ckMd5=2c8ca43685739904; sid=687ihxju; "
                        + "buvid_fp=f4143708344460adbbb91eb07d6564d7; "
                        + "buvid4=5CC226D9-7E45-F132-9A7D-F3C68D77FC8731950-023020321-FRmBv7s/ltliOd9bosY5dQ==");

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
