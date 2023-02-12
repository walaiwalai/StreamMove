package com.sh.config.model.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * @author caiWen
 * @date 2023/1/23 10:27
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ShGlobalConfig {
    private StreamHelperConfig streamerHelper;
    private UploadPersonInfo personInfo;
    private List<StreamerInfo> streamerInfos;
}
