package com.sh.config.exception;

/**
 * @author caiWen
 * @date 2023/1/30 21:41
 */
public enum ErrorEnum {
    /**
     * 上传视频分块失败
     */
    UPLOAD_CHUNK_ERROR(1, "upload chunk error"),

    /**
     * 上传完整视频失败
     */
    POST_WORK_ERROR(2, "post work error"),

    /**
     * 预上传失败
     */
    PRE_UPLOAD_ERROR(3, "pre upload error"),

    /**
     * http请求失败
     */
    HTTP_REQUEST_ERROR(3, "http request error"),

    /**
     * 不合法参数
     */
    INVALID_PARAM(4, "invalid param"),

    /**
     * 处理插件不存在
     */
    PLUGIN_NOT_EXIST(5, "plugin not exist"),

    /**
     * 上传的cookies正在获取
     */
    UPLOAD_COOKIES_IS_FETCHING(6, "upload cookies is fetching"),

    /**
     * ffmpeg执行失败
     */
    FFMPEG_EXECUTE_ERROR(7, "ffmpeg execute error"),
    /**
     * 录制失败
     */
    RECORD_ERROR(8, "record error"),

    /**
     * 录制分片失败
     */
    RECORD_SEG_ERROR(9, "record seg error"),

    /**
     * 录像分辨率太低
     */
    RECORD_BAD_QUALITY(10, "record_bad_quality"),
    ;
    private int errorCode;
    private String message;

    ErrorEnum(int errorCode, String message) {
        this.errorCode = errorCode;
        this.message = message;
    }

    public int getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(int errorCode) {
        this.errorCode = errorCode;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
