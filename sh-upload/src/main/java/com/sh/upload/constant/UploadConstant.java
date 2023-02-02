package com.sh.upload.constant;

import java.util.Map;

/**
 * @author caiWen
 * @date 2023/1/25 19:07
 */
public class UploadConstant {
    public static final String BILI_TOKEN_CHECK_URL_PREFIX = "https://api.snm0516.aisee" +
            ".tv/x/tv/account/myinfo?access_key=";
    public static final String BILI_VIDEO_UPLOAD_APP_SECRET = "af125a0d5279fd576c1b4418a3e8276d";

    public static final String BILI_VIDEO_CHUNK_UPLOAD_URL = "%s?partNumber=%s&uploadId=%s" +
            "&chunks=%s&chunk=%s&size=%s&start=%s&end=%s&total=%s";

    /**
     * 分块上传的状态, 分为未上传，上传失败，上传成功
     */
    public static final int CHUNK_UPLOAD_NO_STATUS = -1;
    public static final int CHUNK_UPLOAD_FAIL_STATUS = 0;
    public static final int CHUNK_UPLOAD_SUCCESS_STATUS = 1;

    public static final String BILI_CHUNK_UPLOAD_FINISH_URL = "%s?output=json&name=%s&profile=ugcfx/bup&uploadId=%s"
            + "&biz_id=%s";


    public static final String BILI_POST_WORK = "https://member.bilibili.com/x/vu/web/add/v3?t=%s&csrf=%s";


    public static final String BILI_UPLOAD_ID = "uploadId";
    public static final String BILI_BIZ_ID = "biz_id";
    public static final String BILI_UPLOAD_URL = "uploadUrl";
    public static final String BILI_UPOS_URI = "upos_uri";
    public static final String BILI_VIDEO_TILE = "title";
    public static final String BILI_VIDEO_DESC = "desc";
    public static final String BILI_VIDEO_DYNAMIC = "dynamic";



}
