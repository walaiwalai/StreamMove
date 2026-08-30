package com.sh.engine.processor.plugin;


/**
 * @Author caiwen
 * @Date 2024 01 26 22 15
 **/
public interface VideoProcessPlugin {
    /**
     * 获取插件唯一标识类型。
     *
     * @return 插件名称（与 ProcessPluginEnum 的 type 一致）
     */
    String getPluginName();

    /**
     * 执行业务处理主流程。
     *
     * @param recordPath 录像目录路径
     * @return 处理成功返回 true；失败返回 false
     */
    boolean process(String recordPath);

    /**
     * 当前插件允许的最大并行执行数量。
     *
     * @return 最大并发数
     */
    int getMaxProcessParallel();
}
