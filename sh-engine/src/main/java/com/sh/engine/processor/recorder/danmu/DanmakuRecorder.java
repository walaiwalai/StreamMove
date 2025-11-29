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
     *
     * @param saveFile 弹幕文件保存地址
     */
    void init(File saveFile);

    /**
     * 刷新弹幕初
     *
     * @param saveFile 弹幕文件保存地址
     */
    void refresh(File saveFile);

    /**
     * 开始弹幕录制
     */
    void start();

    /**
     * 关闭弹幕录制
     */
    void close();

    /**
     * 显示弹幕录制详情
     */
    void showRecordDetail();
}