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
