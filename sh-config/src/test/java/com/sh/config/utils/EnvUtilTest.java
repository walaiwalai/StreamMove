package com.sh.config.utils;

import cn.hutool.extra.spring.SpringUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.support.ResourcePropertySource;

import javax.annotation.PostConstruct;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

public class EnvUtilTest {
    private ApplicationContext previousContext;
    private Object previousBeanFactory;
    private Field beanFactory;

    @Before
    public void saveContext() throws Exception {
        previousContext = SpringUtil.getApplicationContext();
        beanFactory = SpringUtil.class.getDeclaredField("beanFactory");
        beanFactory.setAccessible(true);
        previousBeanFactory = beanFactory.get(null);
    }

    @After
    public void restoreContext() throws Exception {
        new SpringUtil().setApplicationContext(previousContext);
        beanFactory.set(null, previousBeanFactory);
    }

    @Test
    public void roleIsAvailableDuringBeanInitialization() throws Exception {
        for (String role : new String[]{null, "driver", "task"}) {
            try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
                if (role != null) { context.setEnvironment(environment(role)); }
                context.register(SpringUtil.class, StartupProbe.class);
                context.refresh();
                assertEquals("task".equals(role), context.getBean(StartupProbe.class).taskMode);
            }
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void invalidRoleIsNotSilentlyTreatedAsDriver() throws Exception {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.setEnvironment(environment("unknown"));
            new SpringUtil().setApplicationContext(context);
            EnvUtil.isTaskMode();
        }
    }

    public static class StartupProbe {
        private boolean taskMode;
        @PostConstruct
        public void init() { taskMode = EnvUtil.isTaskMode(); }
    }

    private StandardEnvironment environment(String role) throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new ResourcePropertySource("role",
                new ByteArrayResource(("streammove.mode=" + role).getBytes(StandardCharsets.UTF_8))));
        return environment;
    }
}
