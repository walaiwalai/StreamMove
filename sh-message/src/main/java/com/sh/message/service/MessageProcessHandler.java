package com.sh.message.service;

/**
 * 消息后置处理
 * @Author : caiwen
 * @Date: 2024/10/2
 */
public interface MessageProcessHandler {
    void process( String message );

    String getType();
}
