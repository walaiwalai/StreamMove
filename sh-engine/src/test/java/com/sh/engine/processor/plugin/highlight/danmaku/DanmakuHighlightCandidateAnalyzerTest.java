package com.sh.engine.processor.plugin.highlight.danmaku;

import com.sh.engine.model.danmaku.HighlightAnalysisResult;
import com.sh.engine.model.llm.LlmCallResult;
import com.sh.engine.model.llm.LlmResponseException;
import com.sh.engine.service.LlmService;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class DanmakuHighlightCandidateAnalyzerTest {

    @Test
    public void draftShouldRetryOnlyOnceWhenStructuredResponseIsInvalid()
            throws Exception {
        LlmService llmService = mock(LlmService.class);
        HighlightAnalysisCache cache = mock(HighlightAnalysisCache.class);
        HighlightAnalysisAuditRepository auditRepository =
                mock(HighlightAnalysisAuditRepository.class);
        HighlightAnalysisResult expected = new HighlightAnalysisResult();
        when(llmService.chatWithRaw("通用证据", HighlightAnalysisResult.class))
                .thenThrow(new LlmResponseException(
                        "invalid structured response", "bad-envelope"))
                .thenReturn(new LlmCallResult<>("retry-envelope", expected));
        DanmakuHighlightCandidateAnalyzer analyzer =
                new DanmakuHighlightCandidateAnalyzer();
        inject(analyzer, "llmService", llmService);
        inject(analyzer, "analysisCache", cache);
        inject(analyzer, "auditRepository", auditRepository);
        Method method = DanmakuHighlightCandidateAnalyzer.class.getDeclaredMethod(
                "loadOrAnalyzeDraft", String.class, String.class,
                File.class, String.class);
        method.setAccessible(true);

        Object actual = method.invoke(analyzer, "streamer", "cache-key",
                new File("."), "通用证据");

        Assert.assertSame(expected, actual);
        verify(llmService, times(2)).chatWithRaw(
                "通用证据", HighlightAnalysisResult.class);
        verify(auditRepository).append(eq(new File(".")), eq("draft-error"),
                eq("cache-key"), eq("通用证据"), eq("bad-envelope"),
                org.mockito.ArgumentMatchers.any(), isNull());
        verify(auditRepository).append(eq(new File(".")), eq("draft-retry"),
                eq("cache-key"), eq("通用证据"), eq("retry-envelope"),
                eq(expected), isNull());
        verify(cache).saveAnalysis("streamer", "cache-key", expected);
    }

    private void inject(Object target, String fieldName, Object value) throws Exception {
        Field field = DanmakuHighlightCandidateAnalyzer.class
                .getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
