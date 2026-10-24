package com.sh.config.model.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @Author caiwen
 * @Date 2023 12 17 16 31
 **/
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class InitConfig {
    // **********************录制配置**********************
    /**
     * 房间检查时间cron表达式
     */
    private String roomCheckCron;
    /**
     * 回收文件cron表达式
     */
    private String fileCleanCron;
    /**
     * 网页上传平台登录态主动检查和保活 cron，默认每 6 小时执行一次
     */
    private String webUploaderLoginCheckCron;
    /**
     * 旧配置名，保留用于兼容已经部署的 init.json
     */
    private String wechatVideoLoginCheckCron;
    /**
     * 刷新配置cron
     */
    private String configRefreshCron;

}
