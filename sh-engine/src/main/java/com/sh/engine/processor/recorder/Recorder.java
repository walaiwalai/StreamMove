package com.sh.engine.processor.recorder;

import java.util.Date;

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

    public Recorder(Date regDate) {
        this.regDate = regDate;
    }

    public Date getRegDate() {
        return regDate;
    }

    /**
     * 进行录制
     */
    public abstract void doRecord(String savePath);

}
