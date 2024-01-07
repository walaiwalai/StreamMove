package com.sh.config.exception;

/**
 * @author caiWen
 * @date 2023/1/30 21:36
 */
public class StreamerRecordException extends RuntimeException {
    private ErrorEnum errorEnum;

    public StreamerRecordException(ErrorEnum errorEnum) {
        super(errorEnum.getMessage());
        this.errorEnum = errorEnum;
    }
}
