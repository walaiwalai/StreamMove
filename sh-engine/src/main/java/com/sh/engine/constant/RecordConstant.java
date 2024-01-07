package com.sh.engine.constant;

/**
 * @author caiWen
 * @date 2023/1/25 19:07
 */
public class RecordConstant {
    public static final String BILI_TOKEN_CHECK_URL_PREFIX = "https://api.snm0516.aisee" +
            ".tv/x/tv/account/myinfo?access_key=";
    public static final String BILI_VIDEO_UPLOAD_APP_SECRET = "af125a0d5279fd576c1b4418a3e8276d";

    public static final String BILI_VIDEO_CHUNK_UPLOAD_URL = "{uploadUrl}?partNumber={partNumber}&uploadId={uploadId}" +
            "&chunk={chunk}&chunks={chunks}&size={size}&start={start}&end={end}&total={total}";

    public static final String BILI_WEB_PRE_UPLOAD_URL = "https://member.bilibili.com/preupload?name={name}&size={size}&r=upos&profile=ugcupos%2Fbup&ssl=0&version=2.7.1&build=2070100&os=upos&upcdn=ws";

    public static final String BILI_CHUNK_UPLOAD_FINISH_URL = "%s?output=json&name=%s&profile=ugcfx/bup&uploadId=%s"
            + "&biz_id=%s";

    public static final String BILI_CLIENT_PRE_URL
            = "https://member.bilibili.com/preupload?access_key={accessToken}&mid={mid}&profile=ugcfr%2Fpc3";

    public static final String BILI_POST_WORK = "https://member.bilibili.com/x/vu/web/add/v3?t={t}&csrf={csrf}";


    public static final String BILI_UPLOAD_ID = "uploadId";
    public static final String BILI_BIZ_ID = "biz_id";
    public static final String BILI_UPLOAD_URL = "uploadUrl";
    public static final String BILI_UPOS_URI = "upos_uri";
    public static final String BILI_UPOS_AUTH = "x-upos-auth";
    public static final String BILI_VIDEO_TILE = "title";
    public static final String SERVER_FILE_NAME = "serverFileName";


    public static final String LIVING_STREAM_TYPE = "living";
    public static final String RECORD_STREAM_TYPE = "record";



}
