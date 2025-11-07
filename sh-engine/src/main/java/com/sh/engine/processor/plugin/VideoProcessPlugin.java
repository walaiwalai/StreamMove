package com.sh.engine.processor.plugin;


/**
 * @Author caiwen
 * @Date 2024 01 26 22 15
 **/
public interface VideoProcessPlugin {
    /**
     * 获取插件名称
     */
    String getPluginName();

    /**
     * 处理逻辑
     */
    boolean process(String recordPath);

    /**
     * 最大并行处理量
     */
    int getMaxProcessParallel();
}
