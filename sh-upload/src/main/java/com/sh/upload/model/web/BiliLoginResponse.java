package com.sh.upload.model.web;

import com.alibaba.fastjson.annotation.JSONField;

/**
 * @author caiWen
 * @date 2023/1/25 19:23
 */
public class BiliLoginResponse {
    @JSONField(name="is_new")
    private boolean isNew;

    @JSONField(name="token_info")
    private TokenInfo tokenInfo;

    public boolean getIsNew() {
        return isNew;
    }

    public void setIsNew(boolean isNew) {
        this.isNew = isNew;
    }

    public TokenInfo getTokenInfo() {
        return tokenInfo;
    }

    public void setTokenInfo(TokenInfo tokenInfo) {
        this.tokenInfo = tokenInfo;
    }

    static class TokenInfo {
        private Long mid;

        @JSONField(name="access_token")
        private String accessToken;

        @JSONField(name="refresh_token")
        private String refreshToken;

        @JSONField(name="expires_in")
        private Long expiresIn;

        public Long getMid() {
            return mid;
        }

        public void setMid(Long mid) {
            this.mid = mid;
        }

        public String getAccessToken() {
            return accessToken;
        }

        public void setAccessToken(String accessToken) {
            this.accessToken = accessToken;
        }

        public String getRefreshToken() {
            return refreshToken;
        }

        public void setRefreshToken(String refreshToken) {
            this.refreshToken = refreshToken;
        }

        public Long getExpiresIn() {
            return expiresIn;
        }

        public void setExpiresIn(Long expiresIn) {
            this.expiresIn = expiresIn;
        }
    }
}
