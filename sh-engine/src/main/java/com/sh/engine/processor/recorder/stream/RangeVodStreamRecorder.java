package com.sh.engine.processor.recorder.stream;

import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.Date;
import java.util.Map;

/**
 * 先以并发 Range 下载完整 VOD，再复用本地 FFmpeg 录制流程进行切片。
 */
@Slf4j
public class RangeVodStreamRecorder extends StreamRecorder {
    private static final String WORK_DIRECTORY_NAME = ".streamrecorder-range-download";

    private final String streamUrl;

    public RangeVodStreamRecorder(Date regDate,
                                  String roomUrl,
                                  Integer streamChannelType,
                                  String streamUrl,
                                  Map<String, String> extraInfo) {
        super(regDate, roomUrl, streamChannelType, extraInfo);
        this.streamUrl = streamUrl;
    }

    /**
     * 下载完成后才启动本地 FFmpeg；FFmpeg 失败时保留源文件和续传标记。
     */
    @Override
    public void start(String savePath) {
        RangeVodDownloader downloader = new RangeVodDownloader();
        File workDirectory = new File(savePath, WORK_DIRECTORY_NAME);
        File sourceFile = downloader.download(streamUrl, workDirectory);

        StreamUrlStreamRecorder localRecorder = new StreamUrlStreamRecorder(
                regDate, roomUrl, streamChannelType, sourceFile.getAbsolutePath(), extraInfo, true);
        localRecorder.start(savePath);
        try {
            downloader.cleanup(workDirectory);
        } catch (RuntimeException e) {
            log.warn("Range VOD source cleanup failed after successful local recording, path: {}",
                    workDirectory.getAbsolutePath(), e);
        }
    }

    @Override
    protected void initParam(String savePath) {
    }
}
