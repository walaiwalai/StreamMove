package com.sh.engine.model.alidriver;

/**
 * 阿里云盘的仓库类
 */

public class AliStoreBucket {
    private String refreshToken;
    private String driveId;
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

    public AliStoreBucket(String refreshToken, String driveId, String rootId) {
        this.refreshToken = refreshToken;
        this.driveId = driveId;
        this.rootId = rootId;
    }

    public AliStoreBucket(String refreshToken, String driveId) {
        this.refreshToken = refreshToken;
        this.driveId = driveId;
        this.rootId = "root";
    }
}
