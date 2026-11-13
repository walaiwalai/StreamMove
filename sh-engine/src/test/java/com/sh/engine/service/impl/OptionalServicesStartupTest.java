package com.sh.engine.service.impl;

import com.sh.engine.service.AsrService;
import com.sh.engine.service.LlmService;
import com.sh.engine.service.OssUploadService;
import com.sh.engine.service.impl.asr.AliyunAsrServiceImpl;
import com.sh.engine.service.impl.oss.AliyunOssUploadServiceImpl;
import org.junit.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.junit.Assert.assertNotNull;

public class OptionalServicesStartupTest {

    @Test
    public void startsWithoutOptionalServiceConfiguration() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(AliyunAsrServiceImpl.class, AliyunOssUploadServiceImpl.class, LlmServiceImpl.class);
            context.refresh();

            assertNotNull(context.getBean(AsrService.class));
            assertNotNull(context.getBean(OssUploadService.class));
            assertNotNull(context.getBean(LlmService.class));
        }
    }
}
