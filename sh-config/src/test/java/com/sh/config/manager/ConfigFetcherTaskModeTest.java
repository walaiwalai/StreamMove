package com.sh.config.manager;

import cn.hutool.extra.spring.SpringUtil;
import com.sh.config.repo.StreamerRepoService;
import org.junit.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;

import static org.mockito.Mockito.*;

public class ConfigFetcherTaskModeTest {
    @Test
    public void taskStartupDoesNotRequireInitJsonOrLoadStreamers() throws Exception {
        ApplicationContext previous = SpringUtil.getApplicationContext();
        try (StaticApplicationContext context = new StaticApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("role",
                    Collections.singletonMap("streammove.mode", "task")));
            new SpringUtil().setApplicationContext(context);
            ConfigFetcher fetcher = new ConfigFetcher();
            StreamerRepoService repo = mock(StreamerRepoService.class);
            Field dependency = ConfigFetcher.class.getDeclaredField("streamerRepoService");
            dependency.setAccessible(true); dependency.set(fetcher, repo);
            Method init = ConfigFetcher.class.getDeclaredMethod("init");
            init.setAccessible(true); init.invoke(fetcher);
            verifyZeroInteractions(repo);
        } finally {
            new SpringUtil().setApplicationContext(previous);
        }
    }
}
