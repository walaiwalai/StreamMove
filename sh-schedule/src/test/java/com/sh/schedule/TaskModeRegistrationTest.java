package com.sh.schedule;

import cn.hutool.extra.spring.SpringUtil;
import org.junit.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.lang.reflect.Field;
import java.util.Collections;

import static org.junit.Assert.assertNull;

public class TaskModeRegistrationTest {
    @Test
    public void taskModeDoesNotLoadBusinessCronOrStartQuartz() throws Exception {
        ApplicationContext previous = SpringUtil.getApplicationContext();
        try (StaticApplicationContext context = new StaticApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("role",
                    Collections.singletonMap("streammove.mode", "task")));
            new SpringUtil().setApplicationContext(context);
            ProcessWorkerRegistryManager registry = new ProcessWorkerRegistryManager();
            StreamerRecordProcessScheduler scheduler = new StreamerRecordProcessScheduler();
            registry.onApplicationEvent(new ContextRefreshedEvent(context));
            scheduler.init(); scheduler.shutdown();
            Field field = StreamerRecordProcessScheduler.class.getDeclaredField("scheduler");
            field.setAccessible(true); assertNull(field.get(scheduler));
        } finally {
            new SpringUtil().setApplicationContext(previous);
        }
    }
}
