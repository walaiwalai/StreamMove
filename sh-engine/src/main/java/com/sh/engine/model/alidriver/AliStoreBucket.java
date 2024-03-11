package com.sh.engine.model.alidriver;

import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.Maps;
import com.sh.config.utils.HttpClientUtil;

import java.io.IOException;
import java.util.Map;

/**
 * 阿里云盘的仓库类
 */

public class AliStoreBucket {
    private String refreshToken;
    private String driveId;
    private String userId;
    private String rootId;
    private String accessToken;
    private long accessTokenTime = 0;


    public String getDriveId() {
        return driveId;
    }

    public void setDriveId(String driveId) {
        this.driveId = driveId;
    }

    public String getRefreshToken() {
        return refreshToken;
    }

    public void setRefreshToken(String refreshToken) {
        this.refreshToken = refreshToken;
    }

    public String getRootId() {
        return rootId;
    }

    public void setRootId(String rootId) {
        this.rootId = rootId;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public long getAccessTokenTime() {
        return accessTokenTime;
    }

    public void setAccessTokenTime(long accessTokenTime) {
        this.accessTokenTime = accessTokenTime;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public AliStoreBucket(String refreshToken, String driveId, String rootId) {
        this.refreshToken = refreshToken;
        this.driveId = driveId;
        this.rootId = rootId;
    }

    public AliStoreBucket(String refreshToken) {
        this.refreshToken = refreshToken;
        this.rootId = "root";
    }

    public void refreshToken() {
        final String api = "https://api.aliyundrive.com/token/refresh";
        Map<String, String> headers = Maps.newHashMap();
        headers.put("Content-Type", "application/json;charset=UTF-8");
        Map<String, String> data = Maps.newHashMap();
        data.put("refresh_token", this.refreshToken);
        String resp = HttpClientUtil.sendPost(api, headers, data);

        JSONObject object = JSONObject.parseObject(resp);
        this.accessToken = object.getString("access_token");
        this.refreshToken = object.getString("refresh_token");
        this.driveId = object.getString("default_drive_id");
        this.userId = object.getString("user_id");
        this.accessTokenTime = System.currentTimeMillis();
    }
}
