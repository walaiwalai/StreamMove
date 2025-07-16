package com.sh.engine.processor.plugin;

import com.sh.config.manager.ConfigFetcher;
import com.sh.config.model.config.StreamerConfig;
import com.sh.engine.base.StreamerInfoHolder;
import com.sh.engine.constant.ProcessPluginEnum;
import com.sh.engine.processor.uploader.UploaderFactory;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.List;

/**
 * 作品元数据生成，上传到各个平台需要填写的元数据
 *
 * @Author : caiwen
 * @Date: 2024/9/30
 */
@Component
@Slf4j
public class WorkMetaDataGeneratePlugin implements VideoProcessPlugin {
    @Override
    public String getPluginName() {
        return ProcessPluginEnum.META_DATA_GEN.getType();
    }

    @Override
    public boolean process(String recordPath) {
        String streamerName = StreamerInfoHolder.getCurStreamerName();
        StreamerConfig streamerConfig = ConfigFetcher.getStreamerInfoByName(streamerName);

        // 1. 获取上传平台
        List<String> uploadPlatforms = streamerConfig.getUploadPlatforms();
        if (CollectionUtils.isEmpty(uploadPlatforms)) {
            return true;
        }

        // 2. 根据上传平台生成元数据
        for (String platform : uploadPlatforms) {
            String metaFileName = UploaderFactory.getMetaFileName(platform);
            if (StringUtils.isBlank(metaFileName)) {
                continue;
            }
            File metaFile = new File(recordPath, metaFileName);
            if (metaFile.exists()) {
                log.info("{} video meta file existed, will skip", metaFile.getAbsolutePath());
                continue;
            }
            UploaderFactory.saveMetaData(platform, streamerConfig, recordPath);
        }
        return true;
    }
}
