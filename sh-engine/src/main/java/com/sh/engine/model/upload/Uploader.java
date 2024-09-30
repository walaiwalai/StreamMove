package com.sh.engine.model.upload;

import com.sh.config.manager.CacheManager;
import com.sh.config.model.video.LocalVideo;
import com.sh.message.service.MsgSendService;

import java.util.List;

/**
 * @Author caiwen
 * @Date 2024 09 28 22 26
 **/
public interface Uploader {
    String getType();

    void init();

    /**
     * 初始化上传器
     * 如：用户cookies获取
     */
    void setUp();

    /**
     * 上传
     *
     * @return
     */
    boolean upload(String recordPath) throws Exception;
}
