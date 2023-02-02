package com.sh.config.exception;

/**
 * @author caiWen
 * @date 2023/1/30 21:36
 */
public class StreamerHelperException extends Exception {
    private ErrorEnum errorEnum;

    public StreamerHelperException(ErrorEnum errorEnum) {
        super(errorEnum.getMessage());
        this.errorEnum = errorEnum;
    }
}
