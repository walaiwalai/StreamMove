package com.sh.engine.service;

import com.sh.engine.model.record.Recorder;

/**
 * @Author caiwen
 * @Date 2023 12 19 23 02
 **/
public interface StreamRecordService {
    public void startRecord(Recorder recorder);

    public void startDownload(Recorder recorder);
}
