package com.sh.engine.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
public class StreamRecordEndEvent extends ApplicationEvent {
    private final String streamName;

    public StreamRecordEndEvent(Object source, String streamName) {
        super(source);
        this.streamName = streamName;
    }
}
