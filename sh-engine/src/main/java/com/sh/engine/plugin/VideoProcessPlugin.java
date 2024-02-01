package com.sh.engine.plugin;

import java.io.File;
import java.util.List;

/**
 * @Author caiwen
 * @Date 2024 01 26 22 15
 **/
public interface VideoProcessPlugin {
    String getPluginName();

    boolean process();
}
