package com.sh.schedule.worker;

import com.alibaba.fastjson.JSON;
import com.sh.config.model.stauts.FileStatusModel;
import com.sh.upload.service.RecordUploadService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.File;
import java.io.IOException;
import java.util.Collection;

import static com.sh.config.constant.StreamHelperConstant.RECORD_ROOT_PATH;

/**
 * @author caiWen
 * @date 2023/2/1 23:07
 */
@Slf4j
public class RecordUploadWorker extends ProcessWorker {
    @Autowired
    RecordUploadService recordUploadService;



    @Override
    protected void executeJob(JobExecutionContext jobExecutionContext) {
        Collection<File> files = FileUtils.listFiles(new File(RECORD_ROOT_PATH), new String[]{"json"}, true);
        if (CollectionUtils.isEmpty(files)) {
            return;
        }

        for (File file : files) {
            try {
                FileStatusModel fileStatusModel = JSON.parseObject(IOUtils.toString(file.toURI(), "utf-8"),
                        FileStatusModel.class);
                if (fileStatusModel.getUploadLocalFile() && BooleanUtils.isNotTrue(fileStatusModel.getIsPost())) {
                    // 上传视频
                    recordUploadService.upload(fileStatusModel);
                }
            } catch (IOException e) {
                log.error("fuck!", e);
            }
        }
    }
}
