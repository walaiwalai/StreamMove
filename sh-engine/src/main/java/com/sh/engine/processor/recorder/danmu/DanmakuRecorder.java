package com.sh.engine.processor.recorder.danmu;

import java.io.File;

/**
 * 弹幕录制机
 *
 * @Author caiwen
 * @Date 2025 08 29 23 05
 **/
public interface DanmakuRecorder {
    /**
     * 弹幕初始化
     */
    void init();

    /**
     * 开始弹幕录制
     */
    void start();

    /**
     * 关闭弹幕录制
     */
    void close();

    /**
     * 获取本次弹幕录制的输出文件。
     *
     * @return 弹幕文件，初始化完成前可能为 {@code null}
     */
    File getSaveFile();
}
