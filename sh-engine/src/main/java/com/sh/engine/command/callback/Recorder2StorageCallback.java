package com.sh.engine.command.callback;

import com.sh.engine.processor.uploader.ObjectStorageUploader;
import com.sh.engine.service.upload.NetDiskCopyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * @Author caiwen
 * @Date 2025 08 22 23 14
 **/
@Slf4j
@Component
public class Recorder2StorageCallback implements SegmentCallback {
    @Resource
    private NetDiskCopyService netDiskCopyService;
    @Resource
    private ObjectStorageUploader objectStorageUploader;

    @Override
    public void onSegmentCompleted(String segmentFilePath) {
        netDiskCopyService.copyFileToNetDisk(objectStorageUploader)
        log.info("segment completed callback excuse success, segment: {}", segmentFilePath);
    }
}
