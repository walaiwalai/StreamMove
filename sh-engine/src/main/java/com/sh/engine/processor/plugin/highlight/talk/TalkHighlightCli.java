package com.sh.engine.processor.plugin.highlight.talk;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import com.sh.config.exception.ErrorEnum;
import com.sh.config.exception.StreamerRecordException;
import com.sh.engine.model.Streamer;
import com.sh.engine.model.StreamerInfoHolder;
import com.sh.engine.service.impl.LlmServiceImpl;
import com.sh.engine.service.impl.VideoMergeServiceImpl;
import com.sh.engine.service.impl.asr.AliyunAsrServiceImpl;
import com.sh.engine.service.impl.oss.AliyunOssUploadServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.io.support.ResourcePropertySource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 谈话直播离线选片命令行入口，仅加载 ASR、OSS、LLM 和视频合成所需 Bean。
 */
@Slf4j
public final class TalkHighlightCli {
    private static final Pattern STREAMER_PATTERN = Pattern.compile("【([^】]+)】");
    private static final int DEFAULT_MAX_CLIPS = 5;
    private static final String INPUT_ENVIRONMENT_VARIABLE = "TALK_HIGHLIGHT_INPUT";
    private static final String STREAMER_ENVIRONMENT_VARIABLE = "TALK_HIGHLIGHT_STREAMER";
    private static final String MAX_CLIPS_ENVIRONMENT_VARIABLE = "TALK_HIGHLIGHT_MAX_CLIPS";

    private TalkHighlightCli() {
    }

    /**
     * 用法：TalkHighlightCli &lt;视频目录&gt; [--streamer=主播名] [--max-clips=5]。
     */
    public static void main(String[] args) throws IOException {
        configureLogging();
        CliOptions options = CliOptions.parse(args);
        AnnotationConfigApplicationContext context = createContext();
        Streamer streamer = new Streamer();
        streamer.setName(options.streamerName);
        streamer.setRecordPaths(new ArrayList<>());
        StreamerInfoHolder.addStreamer(streamer);
        try {
            TalkHighlightProcessor processor = context.getBean(TalkHighlightProcessor.class);
            TalkHighlightProcessor.TalkHighlightRunResult result = processor.process(
                    options.inputDirectory, options.streamerName, options.maxClips);
            log.info("Talk highlight processing completed, workspace: {}", result.getWorkspaceDirectory());
            for (TalkHighlightProcessor.RenderedClip clip : result.getClips()) {
                TalkHighlightProcessor.TalkHighlightCandidate candidate = clip.getCandidate();
                log.info("Generated clip, range: {}-{}s, score: {}, title: {}, file: {}",
                        candidate.getStartSeconds(), candidate.getEndSeconds(), candidate.getScore(),
                        candidate.getTitle(), clip.getVideoFile());
            }
        } finally {
            StreamerInfoHolder.clear();
            context.close();
        }
    }

    /**
     * CLI 默认只保留业务级日志，并屏蔽可能包含请求头、签名 URL、音频字节或完整转写的调试输出。
     */
    private static void configureLogging() {
        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        loggerContext.getLogger(Logger.ROOT_LOGGER_NAME).setLevel(Level.INFO);
        loggerContext.getLogger("org.apache.http").setLevel(Level.WARN);
        loggerContext.getLogger("com.alibaba.dashscope").setLevel(Level.WARN);
        loggerContext.getLogger("okhttp3").setLevel(Level.WARN);
        loggerContext.getLogger("com.sh.engine.service.impl.LlmServiceImpl").setLevel(Level.WARN);
    }

    private static AnnotationConfigApplicationContext createContext() throws IOException {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addLast(
                new ResourcePropertySource("talk-highlight-dev", "classpath:application-dev.properties"));
        context.register(TalkHighlightCliConfiguration.class);
        context.refresh();
        return context;
    }

    @Configuration
    @Import({
            TalkHighlightProcessor.class,
            AliyunAsrServiceImpl.class,
            AliyunOssUploadServiceImpl.class,
            LlmServiceImpl.class,
            VideoMergeServiceImpl.class
    })
    static class TalkHighlightCliConfiguration {
        @Bean
        public static PropertySourcesPlaceholderConfigurer propertySourcesPlaceholderConfigurer() {
            return new PropertySourcesPlaceholderConfigurer();
        }
    }

    private static final class CliOptions {
        private final File inputDirectory;
        private final String streamerName;
        private final int maxClips;

        private CliOptions(File inputDirectory, String streamerName, int maxClips) {
            this.inputDirectory = inputDirectory;
            this.streamerName = streamerName;
            this.maxClips = maxClips;
        }

        /**
         * 解析位置参数和两个可选参数，未知参数直接拒绝，避免误处理错误目录。
         */
        private static CliOptions parse(String[] args) {
            String configuredInput = args != null && args.length > 0
                    ? args[0] : System.getenv(INPUT_ENVIRONMENT_VARIABLE);
            if (configuredInput == null || configuredInput.trim().isEmpty()) {
                throw new StreamerRecordException(
                        ErrorEnum.INVALID_PARAM,
                        "usage: TalkHighlightCli <video-directory> [--streamer=name] [--max-clips=5] "
                                + "or set " + INPUT_ENVIRONMENT_VARIABLE);
            }
            File inputDirectory = new File(configuredInput.trim());
            String streamerName = inferStreamerName(inputDirectory.getName());
            int maxClips = DEFAULT_MAX_CLIPS;
            String environmentStreamer = System.getenv(STREAMER_ENVIRONMENT_VARIABLE);
            if (environmentStreamer != null && !environmentStreamer.trim().isEmpty()) {
                streamerName = environmentStreamer.trim();
            }
            String environmentMaxClips = System.getenv(MAX_CLIPS_ENVIRONMENT_VARIABLE);
            if (environmentMaxClips != null && !environmentMaxClips.trim().isEmpty()) {
                maxClips = Integer.parseInt(environmentMaxClips.trim());
            }
            int firstOptionIndex = args != null && args.length > 0 ? 1 : 0;
            for (int index = firstOptionIndex; args != null && index < args.length; index++) {
                String argument = args[index];
                if (argument.startsWith("--streamer=")) {
                    streamerName = argument.substring("--streamer=".length()).trim();
                } else if (argument.startsWith("--max-clips=")) {
                    maxClips = Integer.parseInt(argument.substring("--max-clips=".length()).trim());
                } else {
                    throw new StreamerRecordException(
                            ErrorEnum.INVALID_PARAM, "unknown talk highlight argument: " + argument);
                }
            }
            return new CliOptions(inputDirectory, streamerName, maxClips);
        }

        private static String inferStreamerName(String directoryName) {
            Matcher matcher = STREAMER_PATTERN.matcher(directoryName);
            return matcher.find() ? matcher.group(1) : directoryName;
        }
    }
}
