package com.sh.config.model.config;

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
public class StreamerInfo {
    private String name;
    private Boolean uploadLocalFile;
    private Boolean deleteLocalFile;
    private String templateTitle;
    private Integer delayTime;
    private String desc;
    private String source;
    private String dynamic;
    private Integer copyright;
    private String roomUrl;
    private Integer tid;
    private List<String> tags;
    private String cover;
}
