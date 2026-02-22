package com.sh.engine.service.impl.asr;

import com.sh.engine.model.asr.AsrSegment;
import com.sh.engine.service.AsrService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.Collections;
import java.util.List;

/**
 * No-op implementation of AsrService.
 * <p>
 * This implementation returns an empty list and is used when ASR is disabled
 * or no ASR provider is configured.
 *
 * @Author : caiwen
 * @Date: 2026/2/19
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "asr.provider", havingValue = "none", matchIfMissing = true)
public class AsrServiceNoopImpl implements AsrService {

    @Override
    public List<AsrSegment> transcribeSegment(File videoFile, int startSeconds, int endSeconds) {
        log.debug("No-op ASR service called for video: {}, segment: {}s to {}s",
                videoFile.getName(), startSeconds, endSeconds);
        return Collections.emptyList();
    }
}
