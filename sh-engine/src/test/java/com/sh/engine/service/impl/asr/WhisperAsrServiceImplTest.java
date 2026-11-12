package com.sh.engine.service.impl.asr;

import com.sh.engine.model.asr.AsrSegment;
import com.sh.engine.service.AsrService;
import org.junit.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import java.util.*;
import static org.junit.Assert.*;

public class WhisperAsrServiceImplTest {
    @Test public void convertsCandidateTimesBackToSourceTime() {
        List<AsrSegment> rows = WhisperAsrServiceImpl.parse("{\"segments\":[{\"start\":1.3,\"end\":2.7,\"text\":\"看这里\"}]}", 500);
        assertEquals(501, rows.get(0).getStartTime());
        assertEquals(503, rows.get(0).getEndTime());
        try { WhisperAsrServiceImpl.parse("{\"text\":\"no timestamps\"}", 0); fail("missing segments accepted"); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("segments")); }
    }
    @Test public void whisperSelectionDoesNotRegisterTwoAsrProviders() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Collections.singletonMap("asr.provider", "whisper")));
            context.register(WhisperAsrServiceImpl.class, AliyunAsrServiceImpl.class); context.refresh();
            assertEquals(1, context.getBeansOfType(AsrService.class).size());
            assertTrue(context.getBean(AsrService.class) instanceof WhisperAsrServiceImpl);
        }
    }
}
