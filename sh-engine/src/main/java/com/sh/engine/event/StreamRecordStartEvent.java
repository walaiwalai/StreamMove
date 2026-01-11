package com.sh.engine.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.util.Date;

/**
 * 录制开始事件
 */
@Getter
public class StreamRecordStartEvent extends ApplicationEvent {
    private final String streamName;
    private final Date recordAt;

    public StreamRecordStartEvent(Object source, String streamName, Date recordAt) {
        super(source);
        this.streamName = streamName;
        this.recordAt = recordAt;
    }
}