package com.sh.engine.model.record;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;

import java.util.Date;
import java.util.List;

/**
 * @Author caiwen
 * @Date 2023 12 17 17 47
 **/
@Data
@AllArgsConstructor
@Builder
public class RecordStream {
    /**
     * 直播用户名字
     */
    private String anchorName;

    private String roomTitle;

    /**
     * 记录时间（直播当前时间/录播上传时间）
     */
    private Date regDate;

    /**
     * 直播：直播的url
     */
    private String livingStreamUrl;

    /**
     * 录播：最近的录播流url
     */
    private String latestReplayStreamUrl;

    /**
     * 录播：切片详情
     */
    private List<TsRecordInfo> tsViews;

    public String fetchStreamUrl() {
        return StringUtils.isNotBlank(livingStreamUrl) ? livingStreamUrl : latestReplayStreamUrl;
    }
}
