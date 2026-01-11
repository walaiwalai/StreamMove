package com.sh.config.model.config;

import com.google.common.collect.Lists;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

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
    /**
     * 最大录制次数
     */
    private Integer maxRecordingCount = 2;

    // *********************投稿配置**********************
    /**
     * 投稿时忽略小于此大小的文件(M)
     */
    private Integer videoPartLimitSize = 0;

    /**
     * 录制格式
     * ts/mp4
     */
    private String recordFormat;

    // *********************twitchApi请求header Authorization**********************
    private String twitchAuthorization;

    // *********************小红书cookies**********************
    private String xhsCookies;

    // *********************sooplive的cookies**********************
    private String soopCookies;
    private String soopUserName;
    private String soopPassword;

    // *********************淘宝直播**********************
    private String taobaoCookies;

    // *********************斗鱼移动端直播**********************
    private String douyuCookies;

    // *********************快手**********************
    private String kuaishouCookies;

    // *********************kick**********************


    // *********************上传网站配置**********************
    // *********************B站视频上传**********************
    private String biliCookies;


    // *********************其他配置**********************
    /**
     * 采用outApi形式检测房间是否开播
     */
    private List<String> outApiRoomCheckPlatforms = Lists.newArrayList();
}
