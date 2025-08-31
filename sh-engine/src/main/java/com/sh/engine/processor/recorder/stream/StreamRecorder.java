package com.sh.engine.processor.recorder.stream;

import java.util.Date;
import java.util.Map;

/**
 * 录像机
 *
 * @Author caiwen
 * @Date 2024 09 28 10 10
 **/
public abstract class StreamRecorder {
    /**
     * 录像时间
     */
    protected Date regDate;

    protected Map<String, String> extraInfo;

    /**
     * 录像源类型
     */
    protected Integer streamChannelType;

    public StreamRecorder(Date regDate, Integer streamChannelType, Map<String, String> extraInfo) {
        this.regDate = regDate;
        this.streamChannelType = streamChannelType;
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
    public abstract void start(String savePath);

}
