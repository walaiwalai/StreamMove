package com.sh.engine.service;

import com.google.common.collect.Lists;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.sh.config.utils.VideoFileUtils;
import com.sh.engine.base.StreamerInfoHolder;
import com.sh.engine.model.ffmpeg.FfmpegCmd;
import com.sh.engine.model.record.Recorder;
import com.sh.engine.model.record.TsRecordInfo;
import com.sh.engine.util.CommandUtil;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @Author caiwen
 * @Date 2023 12 19 23 09
 **/
@Component
@Slf4j
public class StreamRecordServiceImpl implements StreamRecordService {
    private static final String USER_AGENT = "Mozilla/5.0 (Linux; Android 11; SAMSUNG SM-G973U) AppleWebKit/537.36 (KHTML, like Gecko) SamsungBrowser/14.2 Chrome/87.0.4280.141 Mobile Safari/537.36";
    OkHttpClient CLIENT = new OkHttpClient();
    private static final int SEG_DOWNLOAD_RETRY = 5;

    @Override
    public void startRecord(Recorder recorder) {
        String streamerName = StreamerInfoHolder.getCurStreamerName();
        log.info("begin living download: {}, stream: {}", streamerName, recorder.getStreamUrl());

        livingRecordWithFfmpeg(recorder);
    }

    @Override
    public void startDownload(Recorder recorder) {
        String streamerName = StreamerInfoHolder.getCurStreamerName();
        log.info("begin new video download: {}", streamerName);

        try {
            downloadTs(recorder);
            log.info("new video download finish, stream: {}", streamerName);
        } catch (Exception e) {
            log.error("new video download error, stream: {}", streamerName);
        }
    }

    private void downloadTs(Recorder recorder) throws Exception {
        List<TsRecordInfo> tsViews = recorder.getTsViews();
        String dirName = recorder.getSavePath();
        int total = tsViews.stream().mapToInt(TsRecordInfo::getCount).sum();

        // 每个streamer单独起一个线程池进行下载
        ExecutorService downloadPool = createDownloadPool();

        CountDownLatch downloadLatch = new CountDownLatch(total);
        log.info("total ts count is: {}, begin download...", total);
        AtomicInteger index = new AtomicInteger(1);
        for (TsRecordInfo tsView : tsViews) {
            log.info("download ts record, url: {}, size: {}", tsView.getTsFormatUrl(), tsView.getCount());
            for (int i = 1; i < tsView.getCount() + 1; i++) {
                String segTsUrl = tsView.genTsUrl(i);
                CompletableFuture.supplyAsync(() -> {
                            File targetFile = new File(dirName, VideoFileUtils.genSegName(index.getAndIncrement()));
                            if (targetFile.exists()) {
                                log.info("ts file existed, path: {}", targetFile.getAbsolutePath());
                                return true;
                            }
                            return downloadTsSeg(segTsUrl, targetFile);
                        }, downloadPool)
                        .whenComplete((isSuccess, throwable) -> {
                            downloadLatch.countDown();
                        });
            }
        }

        // 等待视频下载完成
        downloadLatch.await();
    }

    private static ExecutorService createDownloadPool() {
        return new ThreadPoolExecutor(
                4,
                4,
                600,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(20480),
                new ThreadFactoryBuilder().setNameFormat(StreamerInfoHolder.getCurStreamerName() + "-seg-download-%d").build(),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }


    private boolean downloadTsSeg(String targetUrl, File targetFile) {
        for (int i = 0; i < SEG_DOWNLOAD_RETRY; i++) {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
            }

            Request request = new Request.Builder().url(targetUrl).build();
            try (Response response = CLIENT.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    try (FileOutputStream fos = new FileOutputStream(targetFile)) {
                        fos.write(response.body().bytes());
                    }
                    log.info("Seg Video Download finish, filePath: {}", targetFile.getAbsolutePath());
                    return true;
                } else {
                    log.error("Seg Video download failed, url: {}, retry: {}/{}", targetUrl, i + 1, SEG_DOWNLOAD_RETRY);
                }
            } catch (IOException e) {
                log.error("Seg Video calling error, url: {}, retry: {}/{}", targetUrl, i + 1, SEG_DOWNLOAD_RETRY, e);
            }
        }
        return false;
    }

    /**
     * 使用ffmpeg开始拉流
     *
     * @param recorder
     */
    private boolean livingRecordWithFfmpeg(Recorder recorder) {
        FfmpegCmd ffmpegCmd = new FfmpegCmd(buildFfmpegCmd(recorder));
        // 进行拉流，长时间阻塞在这
        Integer resCode = CommandUtil.cmdExec(ffmpegCmd);
        if (resCode == 0) {
            log.info("download stream completed, savePath: {}, code: {}", recorder.getSavePath(), resCode);
            return true;
        } else {
            log.error("download stream fail, savePath: {}, code: {}", recorder.getSavePath(), resCode);
            return false;
        }
    }

    private String buildFfmpegCmd(Recorder recorder) {
        String streamUrl = recorder.getStreamUrl();
        File segFile = new File(recorder.getSavePath(), "seg-%04d.ts");
        // 5s一个片段，跟录像方式统一
        List<String> commands = Lists.newArrayList(
                "-y",
                "-v", "verbose",
                "-rw_timeout", "15000000",
                "-loglevel", "error",
                "-hide_banner",
                "-user_agent", "\"" + USER_AGENT + "\"",
                "-headers", "\"" + buildHeaderStr(recorder.getStreamHeaders()) + "\"",
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
//                "-r", "60",
                "-c:v", "copy",
                "-c:a", "copy",
                "-map", "0",
                "-f", "segment",
                "-segment_time", "4",
                "-segment_start_number", "1",
                "-segment_format", "mp4",
                "-movflags", "+faststart",
                "-reset_timestamps", "1",
                "\"" + segFile.getAbsolutePath() + "\""
        );
        return StringUtils.join(commands, " ");

    }

    private String buildHeaderStr(Map<String, String> headers) {
        if (MapUtils.isEmpty(headers)) {
            return "";
        }
        StringBuilder headerPart = new StringBuilder();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            headerPart.append(entry.getKey()).append(": ").append(entry.getValue());
        }
        return headerPart.toString();
    }

    public static void main(String[] args) {
        StreamRecordServiceImpl streamRecordService = new StreamRecordServiceImpl();
        System.out.println(streamRecordService.buildFfmpegCmd(Recorder.builder()
//                .streamHeaders(ImmutableMap.of("Range", "bytes=0-"))
                .streamUrl("https://b01-kr-naver-vod.pstatic.net/glive/c/read/v2/VOD_ALPHA/glive_2024_06_14_4/74f8d0e2-29a9-11ef-83de-a0369ffac078.mp4?_lsu_sa_=6bd577f851456ef6c0d0e50f6e2507b70e703f28e801ef3534a76ec1c7623eb54e27dad66bc5bc0df07937b29e303b590ec9a1365c57faebacee467ac2469c39c0a238574810856808d238a5e8e833af")
                .savePath("F:\\video\\download\\TheShy")
                .build()));
    }
}
