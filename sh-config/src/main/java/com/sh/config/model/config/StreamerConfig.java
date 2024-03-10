package com.sh.config.model.config;

import com.google.common.collect.Lists;
import com.sh.config.manager.ConfigFetcher;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.commons.lang3.StringUtils;

import java.util.List;

/**
 * @author caiWen
 * @date 2023/1/23 10:45
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class StreamerConfig {
    private String name;
    private String roomUrl;
    private boolean recordWhenOnline;
    private String lastRecordTime;
    private List<String> videoPlugins;
    private List<String> uploadPlatforms = Lists.newArrayList();

    /**
     * 优先以这个为主，没有以init.json的videoSavePath为值
     */
    private String targetSavePath;


    /**
     * b站投稿相关
     */
    private String templateTitle;
    private String desc;
    private String source;
    private String dynamic;
    private Integer tid;
    private List<String> tags;
    private String cover;


    public String fetchSavePath() {
        if (StringUtils.isNotBlank(targetSavePath)) {
            return targetSavePath;
        } else {
            return ConfigFetcher.getInitConfig().getVideoSavePath();
        }
    }

}
