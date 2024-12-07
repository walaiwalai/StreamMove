package com.sh.engine.processor.plugin;


/**
 * @Author caiwen
 * @Date 2024 01 26 22 15
 **/
public interface VideoProcessPlugin {
    String getPluginName();

    boolean process(String recordPath);
}
