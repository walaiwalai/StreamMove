package com.sh.engine.command.callback;

import com.sh.config.model.stauts.FileStatusModel;
import com.sh.config.utils.EnvUtil;
import com.sh.engine.manager.StreamCacheManager;
import com.sh.engine.model.bili.RecordSegmentInfo;
import com.sh.engine.processor.uploader.ObjectStorageUploader;
import com.sh.engine.service.upload.NetDiskCopyService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.File;

/**
 * @Author caiwen
 * @Date 2025 08 22 23 14
 **/
@Slf4j
@Component
public class Recorder2StorageCallback implements RecordCallback {
    @Resource
    private NetDiskCopyService netDiskCopyService;
    @Resource
    private ObjectStorageUploader objectStorageUploader;
    @Resource
    private StreamCacheManager streamCacheManager;

    @Override
    public void onSegmentCompleted( String finishedSegPath, boolean recordEnd ) {
        File segFile = new File(finishedSegPath);
        if (!segFile.exists()) {
            log.error("segment file not exist, segment: {}", finishedSegPath);
            return;
        }

        // 1. 单个视频频段完成，从本地copy到对象存储中
        long start = System.currentTimeMillis();
        netDiskCopyService.copyFileToNetDisk(objectStorageUploader.getRootDirName(), segFile);

        // 2. 拷贝完成, 删除本地文件
        if (EnvUtil.isProd()) {
            FileUtils.deleteQuietly(segFile);
        }

        // 3. 写入缓存
        String recordPath = segFile.getParent();
        String timeV = segFile.getParentFile().getName();



        streamCacheManager.cacheSegmentsInfo(recordPath, segmentInfo);

        // 4. 如果录制完成了
        if (recordEnd) {
            // 修改fileStatus.file文件状态
            FileStatusModel fileStatusModel = FileStatusModel.loadFromFile(recordPath);
            fileStatusModel.postSuccessAll();
        }

        log.info("segment completed callback excuse success, segment: {}, cost: {}ms", finishedSegPath, System.currentTimeMillis() - start);
    }
}
