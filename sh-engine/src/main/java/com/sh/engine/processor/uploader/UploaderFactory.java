package com.sh.engine.processor.uploader;

import com.google.common.collect.Maps;
import com.sh.config.model.config.StreamerConfig;
import com.sh.config.utils.DateUtil;
import com.sh.engine.constant.RecordConstant;
import com.sh.engine.constant.UploadPlatformEnum;
import com.sh.engine.processor.uploader.meta.BiliWorkMetaData;
import com.sh.engine.processor.uploader.meta.DouyinWorkMetaData;
import com.sh.engine.processor.uploader.meta.WechatVideoMetaData;
import com.sh.engine.processor.uploader.meta.WorkMetaData;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.StringSubstitutor;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;

/**
 * @Author : caiwen
 * @Date: 2024/9/29
 */
@Component
public class UploaderFactory {
    @Resource
    private List<Uploader> uploaders;

    /**
     * 上传器
     */
    private static Map<String, Uploader> uploaderMap = Maps.newHashMap();
    private static Map<String, Semaphore> uploaderSemaphoreMap = Maps.newHashMap();

    /**
     * 存储个人信息的key
     */
    private static Map<String, String> accountKeymap = Maps.newHashMap();


    @PostConstruct
    private void init() {
        for (Uploader uploader : uploaders) {
            uploaderMap.put(uploader.getType(), uploader);
            uploaderSemaphoreMap.put(uploader.getType(), new Semaphore(uploader.getMaxUploadParallel(), true));
        }

        accountKeymap.put(UploadPlatformEnum.DOU_YIN.getType(), "douyin-cookies.json");
    }

    public static Uploader getUploader(String type) {
        return uploaderMap.get(type);
    }

    public static String getAccountFileName(String type) {
        return accountKeymap.get(type);
    }

    public static Semaphore getUploaderSemaphore(String type) {
        return uploaderSemaphoreMap.get(type);
    }


    public static class BiliMetaDataBuilder implements MetaDataBuilder {
        @Override
        public WorkMetaData buildMetaData(StreamerConfig streamerConfig, String recordPath) {
            BiliWorkMetaData metaData = new BiliWorkMetaData();
            String title = genTitle(streamerConfig, recordPath);
            metaData.setTitle(title);
            metaData.setDesc(streamerConfig.getDesc());
            metaData.setTags(streamerConfig.getTags());
            metaData.setCover(streamerConfig.getCover());
            metaData.setTid(streamerConfig.getTid());
            metaData.setSource(streamerConfig.getSource());
            metaData.setDynamic(title);
            return metaData;
        }
    }

    public static class DouyinMetaDataBuilder implements MetaDataBuilder {
        @Override
        public WorkMetaData buildMetaData(StreamerConfig streamerConfig, String recordPath) {
            DouyinWorkMetaData metaData = new DouyinWorkMetaData();
            metaData.setTitle(genTitle(streamerConfig, recordPath));
            metaData.setDesc(streamerConfig.getDesc());
            metaData.setTags(streamerConfig.getTags());
            metaData.setLocation(streamerConfig.getLocation());
            metaData.setPreViewFilePath(streamerConfig.getCoverFilePath());
            return metaData;
        }
    }

    public static class WechatMetaDataBuilder implements MetaDataBuilder {
        @Override
        public WorkMetaData buildMetaData(StreamerConfig streamerConfig, String recordPath) {
            WechatVideoMetaData metaData = new WechatVideoMetaData();
            metaData.setTitle(genTitle(streamerConfig, recordPath));
            metaData.setDesc(streamerConfig.getDesc());
            metaData.setTags(streamerConfig.getTags());
            metaData.setPreViewFilePath(streamerConfig.getCoverFilePath());
            return metaData;
        }
    }

    /**
     * 根据标题模板生成视频标题
     *
     * @param streamerConfig 配置
     * @param recordPath     保存路径名称
     * @return 视频标题
     */
    private static String genTitle(StreamerConfig streamerConfig, String recordPath) {
        File danmuFile = new File(recordPath, RecordConstant.DAMAKU_FILE_PREFIX + "P01.mp4");

        String timeV = new File(recordPath).getName();
        Map<String, String> paramsMap = Maps.newHashMap();
        paramsMap.put("time", DateUtil.describeTime(timeV, DateUtil.YYYY_MM_DD_HH_MM_SS_V2));
        paramsMap.put("name", danmuFile.exists() ? streamerConfig.getName() + "-带弹幕" : streamerConfig.getName());

        if (StringUtils.isNotBlank(streamerConfig.getTemplateTitle())) {
            StringSubstitutor sub = new StringSubstitutor(paramsMap);
            return sub.replace(streamerConfig.getTemplateTitle());
        } else {
            return streamerConfig.getName() + " " + timeV + " " + "录播";
        }
    }

    interface MetaDataBuilder {
        WorkMetaData buildMetaData(StreamerConfig config, String recordPath);
    }
}
