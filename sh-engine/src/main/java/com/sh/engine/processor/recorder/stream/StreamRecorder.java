package com.sh.engine.processor.recorder.stream;

import lombok.extern.slf4j.Slf4j;

import java.util.Date;
import java.util.Map;

/**
 * 录像机
 *
 * @Author caiwen
 * @Date 2024 09 28 10 10
 **/
@Slf4j
public abstract class StreamRecorder {
    /**
     * 录像时间
     */
    protected Date regDate;

    /**
     * 录像直播间地址
     */
    protected String roomUrl;

    /**
     * 录像源类型
     */
    protected Integer streamChannelType;

    /**
     * 额外信息
     */
    protected Map<String, String> extraInfo;

    public StreamRecorder(Date regDate, String roomUrl, Integer streamChannelType, Map<String, String> extraInfo) {
        this.regDate = regDate;
        this.roomUrl = roomUrl;
        this.streamChannelType = streamChannelType;
        this.extraInfo = extraInfo;
    }

    public Date getRegDate() {
        return regDate;
    }

    public String getExtraValue(String key) {
        return extraInfo == null ? null : extraInfo.get(key);
    }


    public void init(String savePath) {
        try {
            initParam(savePath);
        } catch (Exception e) {
            log.error("stream init failed, roomUrl: {}", roomUrl, e);
        }
    }

    /**
     * 进行录制
     */
    public abstract void start(String savePath);

    /**
     * 获取录像元信息
     */
    protected abstract void initParam(String savePath);
}
