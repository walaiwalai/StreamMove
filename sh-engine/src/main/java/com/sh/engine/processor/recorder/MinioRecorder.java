package com.sh.engine.processor.recorder;

import com.sh.config.manager.MinioManager;
import lombok.extern.slf4j.Slf4j;

import java.util.Date;

/**
 * 特殊：从minio上下载文件
 *
 * @Author caiwen
 * @Date 2024 10 19 16 36
 **/
@Slf4j
public class MinioRecorder extends Recorder {
    private String objDir;


    public MinioRecorder(String savePath, Date regDate, String objDir) {
        super(savePath, regDate);
        this.objDir = objDir;
    }

    @Override
    public void doRecord() throws Exception {
        MinioManager.down2LocalDir(objDir, savePath);
    }
}
