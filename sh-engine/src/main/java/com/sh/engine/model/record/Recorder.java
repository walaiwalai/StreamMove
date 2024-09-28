package com.sh.engine.model.record;

import java.util.Date;

/**
 * 抽象的录像机
 * @Author caiwen
 * @Date 2024 09 28 10 10
 **/
public abstract class Recorder {
    /**
     * 录播视频保存路径
     * 如：...download/TheShy/2023-02-12
     */
    protected String savePath;

    /**
     * 录像时间
     */
    protected Date regDate;

    public Recorder(String savePath, Date regDate) {
        this.savePath = savePath;
        this.regDate = regDate;
    }

    public String getSavePath() {
        return savePath;
    }

    public Date getRegDate() {
        return regDate;
    }

    /**
     * 进行录制
     */
    public abstract void doRecord() throws Exception;

}
