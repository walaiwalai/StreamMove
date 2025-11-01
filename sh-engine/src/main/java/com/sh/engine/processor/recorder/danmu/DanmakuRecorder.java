package com.sh.engine.processor.recorder.danmu;

import com.sh.config.model.config.StreamerConfig;
import com.sh.engine.model.video.StreamMetaInfo;

/**
 * 弹幕录制机
 *
 * @Author caiwen
 * @Date 2025 08 29 23 05
 **/
public abstract class DanmakuRecorder {
    /**
     * 直播间地址
     */
    protected StreamerConfig config;

    public DanmakuRecorder(StreamerConfig config) {
        this.config = config;
    }

    /**
     * 弹幕初始化
     *
     * @param savePath 弹幕文件保存地址
     */
    public abstract void init(String savePath, StreamMetaInfo metaInfo);

    /**
     * 开始弹幕录制
     */
    public abstract void start();

    /**
     * 关闭弹幕录制
     */
    public abstract void close();
}
