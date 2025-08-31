package com.sh.engine.processor.recorder.danmu;

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
    protected String roomUrl;

    public DanmakuRecorder(String roomUrl) {
        this.roomUrl = roomUrl;
    }

    /**
     * 开始接受弹幕
     *
     * @param savePath 弹幕文件保存地址
     */
    public abstract void init(String savePath);

    /**
     * 关闭弹幕录制
     */
    public abstract void close();
}
