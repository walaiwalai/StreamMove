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

    public static final String BILI_WEB_PRE_UPLOAD_URL = "https://member.bilibili.com/preupload?name={name}&size={size}&r=upos&profile=ugcfx%2Fbup";

    public static final String BILI_CHUNK_UPLOAD_FINISH_URL = "%s?output=json&name=%s&profile=ugcfx/bup&uploadId=%s"
            + "&biz_id=%s";

    public static final String BILI_CLIENT_PRE_URL
            = "https://member.bilibili.com/preupload?access_key={accessToken}&mid={mid}&profile=ugcfr%2Fpc3";

    public static final String BILI_POST_WORK = "https://member.bilibili.com/x/vu/web/add/v3?t={t}&csrf={csrf}";


    /**
     * 精彩区间前后视频端个数
     */
    public static final int POTENTIAL_INTERVAL_PRE_N = 5;
    public static final int POTENTIAL_INTERVAL_POST_N = 1;

    /**
     * 接受空白KAD的阈值（可能存在ocr扫描误判导致kda为空值）
     */
    public static final int KDA_SEQ_WINDOW_SIZE = 5;

    /**
     * 录制重试次数
     */
    public static final int RECORD_RETRY_CNT = 5;

    /**
     * 精彩视频
     */
    public static final String HL_VIDEO = "highlight.mp4";


    /**
     * 封面文件
     */
    public static final String THUMBNAIL_FILE_NAME = "work-thumbnail.jpg";

    public static final String DEFAULT_THUMBNAIL_URL = "/home/admin/stream/thumbnail/default.jpg";
}
