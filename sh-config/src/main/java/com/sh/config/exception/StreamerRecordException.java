package com.sh.config.exception;

/**
 * @author caiWen
 * @date 2023/1/30 21:36
 */
public class StreamerRecordException extends RuntimeException {
    private final ErrorEnum errorEnum;

    public StreamerRecordException(ErrorEnum errorEnum) {
        super(errorEnum.getMessage());
        this.errorEnum = errorEnum;
    }

    public StreamerRecordException(ErrorEnum errorEnum, String message) {
        super(message);
        this.errorEnum = errorEnum;
    }

    public StreamerRecordException(ErrorEnum errorEnum, String message, Throwable cause) {
        super(message, cause);
        this.errorEnum = errorEnum;
    }

    public ErrorEnum getErrorEnum() {
        return errorEnum;
    }
}
