package com.sh.engine.model.bili.web;

import com.alibaba.fastjson.annotation.JSONField;

/**
 * @author caiWen
 * @date 2023/1/25 19:16
 */
public class BiliQRCodeResponse {
    private String url;

    @JSONField(name="auth_code")
    private String authCode;

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getAuthCode() {
        return authCode;
    }

    public void setAuthCode(String authCode) {
        this.authCode = authCode;
    }
}
