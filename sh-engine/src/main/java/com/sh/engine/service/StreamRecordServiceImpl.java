package com.sh.engine.service;

import cn.hutool.core.io.FileUtil;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.sh.config.manager.ConfigFetcher;
import com.sh.engine.manager.StatusManager;
import com.sh.engine.model.ffmpeg.FfmpegCmd;
import com.sh.engine.model.record.RecordTask;
import com.sh.engine.model.record.Recorder;
import com.sh.engine.util.CommandUtil;
import com.sh.engine.util.WebsiteStreamUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * @Author caiwen
 * @Date 2023 12 19 23 09
 **/
@Component
@Slf4j
public class StreamRecordServiceImpl implements StreamRecordService {

    private static Map<String, String> fakeHeaderMap = Maps.newHashMap();
    private static final String USER_AGENT = "User-Agent";

    static {
        fakeHeaderMap.put("Accept", "*/*");
        fakeHeaderMap.put("Accept-Encoding", "gzip, deflate, br");
        fakeHeaderMap.put("Accept-Language", "zh,zh-TW;q=0.9,en-US;q=0.8,en;q=0.7,zh-CN;q=0.6,ru;q=0.5");
        fakeHeaderMap.put("Origin", "https://www.huya.com");
        fakeHeaderMap.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like " +
                "Gecko) Chrome/83.0.4103.106 Safari/537.36");
    }

    @Override
    public void startRecord(Recorder recorder) {
        RecordTask recordTask = recorder.getRecordTask();
        String streamerName = recordTask.getRecorderName();
        log.info("begin download: {}, stream: {}", streamerName, recordTask.getStreamUrl());

        startDownLoadWithFfmpeg(recorder, recordTask);
    }
    /**
     * 使用ffmpeg开始拉流
     *
     * @param recorder
     * @param recordTask
     */
    private boolean startDownLoadWithFfmpeg(Recorder recorder, RecordTask recordTask) {
        File fileToDownload = new File(recorder.getSavePath(),
                recordTask.getRecorderName() + "-part-%03d." + recorder.getVideoExt());

        // 1. 生成拉流命令
//        String command = genFfmpegCmd(recordTask.getStreamUrl(), fileToDownload.getAbsolutePath());
        String command = buildFfmpegCmd(recordTask.getStreamUrl(), fileToDownload.getAbsolutePath());

        // 2. ffmpegCmd命令放到线程池中
        return doFfmpegCmd(new FfmpegCmd(command), recorder);
    }

    private String genFfmpegCmd(String streamUrl, String downloadFileName) {
        String fakeHeaders = "";
        for (String key : fakeHeaderMap.keySet()) {
            if (StringUtils.equals(key, USER_AGENT)) {
                continue;
            }
            fakeHeaders += "$" + key + ":" + fakeHeaderMap.get(key) + "\\r\\n";
        }
        String command = String.format("ffmpeg -headers \"%s\" -user_agent \"%s\" -r 30 -async 1 -i \"%s\" -c:v copy -c:a copy -f segment " +
                        "-segment_time %s -segment_start_number %s \"%s\"",
                fakeHeaders,
                fakeHeaderMap.get(USER_AGENT),
                streamUrl,
                ConfigFetcher.getInitConfig().getSegmentDuration(),
                1,
                downloadFileName
        );
//        String command = String.format(" -headers \"%s\" -user_agent \"%s\" -r 30 -async 1 -i \"%s\" -c:v copy -c:a copy -f segment " +
//                        "-segment_size 1200000000 -segment_start_number %s \"%s\"",
//                fakeHeaders,
//                fakeHeaderMap.get(USER_AGENT),
//                streamUrl,
//                startNumber,
//                downloadFileName
//        );
//        String command = String.format(" -headers \"%s\" -user_agent \"%s\" -r 60  -i \"%s\" " +
//                        "-c:v  libx264 -crf 22 -c:a copy " +
//                        "-f  segment -segment_time %s -segment_start_number %s \"%s\"",
//                fakeHeaders,
//                fakeHeaderMap.get(USER_AGENT),
//                streamUrl,
//                ConfigFetcher.getInitConfig().getSegmentDuration(),
//                startNumber,
//                downloadFileName
//        );

        return command;
    }

    private String buildFfmpegCmd(String streamUrl, String downloadFileName) {
        String userAgent = "Mozilla/5.0 (Linux; Android 11; SAMSUNG SM-G973U) AppleWebKit/537.36 (KHTML, like Gecko) SamsungBrowser/14.2 Chrome/87.0.4280.141 Mobile Safari/537.36";
        List<String> commands = Lists.newArrayList(
                 "-y",
                "-v", "verbose",
                "-rw_timeout", "15000000",
                "-loglevel", "error",
                "-hide_banner",
                "-user_agent", "\"" + userAgent + "\"",
                "-protocol_whitelist", "rtmp,crypto,file,http,https,tcp,tls,udp,rtp",
                "-thread_queue_size", "1024",
                "-analyzeduration", "2147483647",
                "-probesize", "2147483647",
                "-fflags", "+discardcorrupt",
                "-i", "\"" + streamUrl + "\"",
                "-bufsize", "5000k",
                "-sn", "-dn",
                "-reconnect_delay_max", "30",
                "-reconnect_streamed", "-reconnect_at_eof",
                "-max_muxing_queue_size", "64",
                "-correct_ts_overflow", "1",
                "-c:v", "copy",
                "-c:a", "aac",
                "-map", "0",
                "-f", "segment",
                "-segment_time", ConfigFetcher.getInitConfig().getSegmentDuration() + "",
                "-segment_format", "mp4",
                "-movflags", "+faststart",
                "-reset_timestamps", "1",
                "\"" + downloadFileName + "\""
        );
        return StringUtils.join(commands, " ");

    }

    private boolean doFfmpegCmd(FfmpegCmd ffmpegCmd, Recorder recorder) {
        String recorderName = recorder.getRecordTask().getRecorderName();
        // 进行拉流，长时间阻塞在这
        Integer resCode = CommandUtil.cmdExec(ffmpegCmd);
        if (resCode == 0) {
            log.info("download stream completed, recordName: {}, savePath: {}, code: {}", recorderName, recorder.getSavePath(), resCode);
            return true;
        } else {
            log.error("download stream fail, recordName: {}, savePath: {}, code: {}", recorderName, recorder.getSavePath(), resCode);
            return false;
        }
    }
}
