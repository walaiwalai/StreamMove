package com.sh.engine.model.alidriver;

import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.Maps;
import com.sh.config.utils.HttpClientUtil;

import java.util.Map;

/**
 * 阿里云盘的仓库类
 */

public class AliStoreBucket {
    private static final String REFRESH_TOKEN_URL = "https://api.aliyundrive.com/token/refresh";
    private String refreshToken;
    private String driveId;
    private String userId;
    private String rootId;
    private String accessToken;
    private long accessTokenTime = 0;


    public String getDriveId() {
        return driveId;
    }

    public String getRefreshToken() {
        return refreshToken;
    }


    public String getRootId() {
        return rootId;
    }

    public String getAccessToken() {
        // accessToken 有效期是7200秒
        if (accessTokenTime + 7000 * 1000 < System.currentTimeMillis()) {
            refreshToken();
        }
        return accessToken;
    }

    public long getAccessTokenTime() {
        return accessTokenTime;
    }

    public String getUserId() {
        return userId;
    }

    public AliStoreBucket(String refreshToken) {
        this.refreshToken = refreshToken;
        this.rootId = "root";
    }

    public void refreshToken() {
        Map<String, String> headers = Maps.newHashMap();
        headers.put("Content-Type", "application/json;charset=UTF-8");
        Map<String, String> data = Maps.newHashMap();
        data.put("refresh_token", this.refreshToken);
        String resp = HttpClientUtil.sendPost(REFRESH_TOKEN_URL, headers, data);

        JSONObject object = JSONObject.parseObject(resp);
        this.accessToken = object.getString("access_token");
        this.refreshToken = object.getString("refresh_token");
        this.driveId = object.getString("default_drive_id");
        this.userId = object.getString("user_id");
        this.accessTokenTime = System.currentTimeMillis();
    }
}
