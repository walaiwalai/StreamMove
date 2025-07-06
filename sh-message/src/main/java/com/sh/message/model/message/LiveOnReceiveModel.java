package com.sh.message.model.message;

import lombok.Data;

/**
 * @Author caiwen
 * @Date 2025 07 06 12 22
 **/
@Data
public class LiveOnReceiveModel {
    /**
     * 直播包名
     */
    private String from;

    /**
     * 直播间名称
     */
    private String streamerName;

    /**
     * 接收时间
     */
    private String receiveTime;
}
