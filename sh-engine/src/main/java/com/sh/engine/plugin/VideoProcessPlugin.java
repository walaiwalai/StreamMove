package com.sh.engine.plugin;

import com.sh.engine.model.RecordContext;


/**
 * @Author caiwen
 * @Date 2024 01 26 22 15
 **/
public interface VideoProcessPlugin {
    String getPluginName();

    boolean process(RecordContext context);
}
