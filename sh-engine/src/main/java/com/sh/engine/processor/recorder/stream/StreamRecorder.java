package com.sh.engine.processor.recorder.stream;

import com.sh.engine.model.video.StreamMetaInfo;

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

    /**
     * 录像元信息
     */
    protected StreamMetaInfo streamMeta;

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

    public StreamMetaInfo getStreamMeta() {
        return streamMeta;
    }

    public void init() {
        this.streamMeta = fetchMeta();
        if (this.streamMeta.isValid()) {
            return;
        }
        this.streamMeta = fetchMeta();
    }

    /**
     * 进行录制
     */
    public abstract void start(String savePath);

    /**
     * 获取录像元信息
     *
     * @return 元信息
     */
    protected abstract StreamMetaInfo fetchMeta();
}
