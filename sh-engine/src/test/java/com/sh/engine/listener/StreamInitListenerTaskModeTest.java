package com.sh.engine.listener;

import cn.hutool.extra.spring.SpringUtil;
import org.springframework.context.ApplicationContext;
import org.junit.Test;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.util.Collections;

import static org.mockito.Mockito.*;

public class StreamInitListenerTaskModeTest {
    @Test
    public void taskReadyDoesNotInitializeUploadersWithoutStreamerConfiguration() {
        ApplicationContext previous = SpringUtil.getApplicationContext();
        try (StaticApplicationContext context = new StaticApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("role",
                    Collections.singletonMap("streammove.mode", "task")));
            ApplicationReadyEvent event = mock(ApplicationReadyEvent.class);
            new SpringUtil().setApplicationContext(context);
            new StreamInitListener().onApplicationEvent(event);
        } finally {
            new SpringUtil().setApplicationContext(previous);
        }
    }
}
