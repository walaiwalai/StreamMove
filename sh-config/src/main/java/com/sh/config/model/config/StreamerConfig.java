package com.sh.config.model.config;

import com.google.common.collect.Lists;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
    private String templateTitle;
    private String desc;
    private String source;
    private String dynamic;
    private Integer tid;
    private List<String> tags;
    private String cover;

    private boolean recordWhenOnline;
    private String lastRecordTime;

    private List<String> videoPlugins;
}
