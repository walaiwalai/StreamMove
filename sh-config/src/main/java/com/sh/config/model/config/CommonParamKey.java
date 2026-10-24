package com.sh.config.model.config;

/**
 * 可供业务读取的通用参数键。
 */
public enum CommonParamKey {
    /** 历史参数名保留 URL 后缀，参数值实际为 OSS bucket 内的图片对象 key。 */
    BILI_SEASON_COVER_URL("biliSeasonCoverUrl"),
    VIDEO_PART_LIMIT_SIZE("videoPartLimitSize"),
    FAILED_UPLOAD_KEEP_HIGHLIGHT_HOURS("failedUploadKeepHighlightHours"),
    FAILED_UPLOAD_DELETE_HOURS("failedUploadDeleteHours"),
    OUT_API_ROOM_CHECK_PLATFORMS("outApiRoomCheckPlatforms"),
    MAX_RECORDING_COUNT("maxRecordingCount"),
    BILI_COOKIES("biliCookies"),
    XHS_COOKIES("xhsCookies"),
    SOOP_COOKIES("soopCookies"),
    TAOBAO_COOKIES("taobaoCookies"),
    DOUYU_COOKIES("douyuCookies"),
    KUAISHOU_COOKIES("kuaishouCookies"),
    TWITCH_AUTHORIZATION("twitchAuthorization"),
    SOOP_USER_NAME("soopUserName"),
    SOOP_PASSWORD("soopPassword");

    private final String key;

    CommonParamKey(String key) {
        this.key = key;
    }

    public String getKey() {
        return key;
    }
}
