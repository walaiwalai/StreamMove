package com.sh.config.model.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author caiWen
 * @date 2023/1/23 12:09
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class StreamHelperConfig {
    /**
     * 调试开关
     */
    private Boolean debug;
    /**
     * 分多端拉流的每段大小
     */
    private Integer segmentDuration;

    /**
     * 房间检查时间cron表达式
     */
    private String roomCheckCron;

    /**
     *投稿检测cron表达式
     */
    private String recordUploadCron;

    /**
     *回收文件cron表达式
     */
    private String fileCleanCron;

    /**
     * 投稿时忽略小于此大小的文件
     */
    private Integer videoPartLimitSize;

    /**
     * 此级别之上（包括）的日志将被推送，可选"TRACE"|"DEBUG"|"INFO"|"WARN"|"ERROR"。
     */
    private String logLevel;
}
