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
     * 刷新配置cron
     */
    private String configRefreshCron;

    // *********************录制配置**********************
    private Integer maxRecordingCount = 2;

    // *********************投稿配置**********************
    /**
     * 投稿时忽略小于此大小的文件(M)
     */
    private Integer videoPartLimitSize = 0;

    // *********************twitchApi请求header Authorization**********************
    private String twitchAuthorization;

    // *********************小红书cookies**********************
    private String xhsCookies;

    // *********************youtube的cookies**********************
    private String youtubeCookies;

    // *********************sooplive的cookies**********************
    private String soopCookies;
    private String soopUserName;
    private String soopPassword;

    // *********************B站视频上传**********************
    private String biliCookies;
    private String accessToken;
    private Long mid;
}
