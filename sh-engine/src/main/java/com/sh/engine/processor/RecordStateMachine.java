package com.sh.engine.processor;

import cn.hutool.core.util.RandomUtil;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.sh.engine.model.RecordContext;
import com.sh.engine.model.RecordTaskStateEnum;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.List;
import java.util.Map;
import java.util.Random;
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
    @Autowired
    List<AbstractRecordTaskProcessor> processors;
    private static final ExecutorService POOL = new ThreadPoolExecutor(
            4,
            4,
            600,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(10),
            new ThreadFactoryBuilder().setNameFormat("record-state-machine").build(),
            new ThreadPoolExecutor.AbortPolicy()
    );

    private Map<RecordTaskStateEnum, AbstractRecordTaskProcessor> processorMap;
    @PostConstruct
    public void init() {
        processorMap = processors.stream()
                .collect(Collectors.toMap(AbstractRecordTaskProcessor::acceptState, Function.identity(), (a, b) -> b));
    }
    public void start(RecordContext context) {
        Map<String, String> contextMap = MDC.getCopyOfContextMap();
        POOL.submit(() -> {
//            int i = RandomUtil.randomInt(1, 30);
//            try {
//                Thread.sleep(i * 1000);
//            } catch (InterruptedException e) {
//            }
            MDC.setContextMap(contextMap);
            process(context);
        });
    }

    private void process(RecordContext context) {
        int loop = 0;
        while (!context.getState().isFinishedState() && loop++ < 100) {
            AbstractRecordTaskProcessor processor = processorMap.get(context.getState());
            log.info("use {} to do record", processor.getClass().getSimpleName());
            try {
                processor.process(context);
            } catch (Throwable e) {
                log.error("record error, state: {}", processor.getStage().getCode(), e);
                context.setState(RecordTaskStateEnum.ERROR);
            }
        }

        if (loop == 100) {
            throw new RuntimeException("internal reach to max loop");
        }
        log.info("process internal finish, name: {}", context.getName());
    }
}
