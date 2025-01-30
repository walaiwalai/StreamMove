package com.sh.engine.manager;

import com.google.common.collect.Lists;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.sh.config.exception.ErrorEnum;
import com.sh.config.exception.StreamerRecordException;
import com.sh.config.manager.ConfigFetcher;
import com.sh.config.model.config.StreamerConfig;
import com.sh.engine.base.Streamer;
import com.sh.engine.base.StreamerInfoHolder;
import com.sh.engine.constant.RecordTaskStateEnum;
import com.sh.engine.model.RecordContext;
import com.sh.engine.processor.AbstractStageProcessor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.DirectoryFileFilter;
import org.apache.commons.io.filefilter.NameFileFilter;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.File;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * @Author caiwen
 * @Date 2023 12 18 23 08
 **/
@Component
@Slf4j
public class RecordStateMachine {
    @Value("${sh.video-save.path}")
    private String videoSavePath;
    @Autowired
    List<AbstractStageProcessor> processors;
    private static final ExecutorService POOL = new ThreadPoolExecutor(
            4,
            8,
            600,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(64),
            new ThreadFactoryBuilder().setNameFormat("record-state-machine").build(),
            new ThreadPoolExecutor.AbortPolicy()
    );

    private Map<RecordTaskStateEnum, AbstractStageProcessor> processorMap;

    @PostConstruct
    public void init() {
        processorMap = processors.stream()
                .collect(Collectors.toMap(AbstractStageProcessor::acceptState, Function.identity(), (a, b) -> b));
    }

    public void start(StreamerConfig config) {
        Map<String, String> contextMap = MDC.getCopyOfContextMap();
        RecordContext context = new RecordContext();
        context.setState(RecordTaskStateEnum.INIT);

        POOL.submit(() -> {
            MDC.setContextMap(contextMap);
            MDC.put("tranceId", UUID.randomUUID().toString());
            Thread.currentThread().setName("record-state-machine-" + config.getName());

            init(config);
            try {
                process(context);
            } catch (Exception e) {
                log.error("stateMachine error");
            } finally {
                StreamerInfoHolder.clear();
            }
        });
    }

    private void init(StreamerConfig config) {
        String name = config.getName();
        List<String> recordPaths = Lists.newArrayList();

        // 搜索当前streamer下的所有文件夹中的fileStatus.json文件
        File streamerFile = new File(videoSavePath, name);
        if (streamerFile.exists()) {
            Collection<File> statusFiles = FileUtils.listFiles(streamerFile, new NameFileFilter("fileStatus.json"),
                    DirectoryFileFilter.INSTANCE);
            for (File statusFile : statusFiles) {
                recordPaths.add(statusFile.getParent());
            }
        }

        // threadLocal
        Streamer streamer = new Streamer();
        streamer.setName(name);
        streamer.setRecordPaths(recordPaths);
        StreamerInfoHolder.addStreamer(streamer);
    }

    private void process(RecordContext context) {
        int loop = 0;
        while (!context.getState().isFinishedState() && loop++ < 100) {
            AbstractStageProcessor processor = processorMap.get(context.getState());
            try {
                processor.process(context);
            } catch (StreamerRecordException recordException) {
                if (Objects.equals(ErrorEnum.FAST_END.getErrorCode(), recordException.getErrorEnum().getErrorCode())) {
                    context.setState(RecordTaskStateEnum.END);
                } else {
                    log.info("record error, state: {}", processor.getStage().getCode(), recordException);
                    context.setState(RecordTaskStateEnum.ERROR);
                }
            } catch (Throwable e) {
                log.error("record error, state: {}", processor.getStage().getCode(), e);
                context.setState(RecordTaskStateEnum.ERROR);
            }
        }

        if (loop == 100) {
            throw new RuntimeException("internal reach to max loop");
        }
    }
}
