package com.sh.engine.processor.recorder;

import java.util.Date;
import java.util.Map;

/**
 * 抽象的录像机
 *
 * @Author caiwen
 * @Date 2024 09 28 10 10
 **/
public abstract class Recorder {
    /**
     * 录像时间
     */
    protected Date regDate;

    protected Map<String, String> extraInfo;

    public Recorder( Date regDate, Map<String, String> extraInfo ) {
        this.regDate = regDate;
        this.extraInfo = extraInfo;
    }

    public Date getRegDate() {
        return regDate;
    }

    public String getExtraValue(String key) {
        return extraInfo == null ? null : extraInfo.get(key);
    }



    /**
     * 进行录制
     */
    public abstract void doRecord(String savePath);

}
