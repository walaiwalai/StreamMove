package com.sh.engine.processor.recorder;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.sh.config.utils.VideoFileUtil;
import com.sh.engine.constant.RecordConstant;
import com.sh.engine.model.ffmpeg.FfmpegRecordCmd;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.util.Date;
import java.util.List;

/**
 * @Author : caiwen
 * @Date: 2025/1/28
 */
@Slf4j
public class StreamUrlRecorder extends Recorder {
    private String streamUrl;

    public StreamUrlRecorder(Date regDate, String streamUrl) {
        super(regDate, Maps.newHashMap());
        this.streamUrl = streamUrl;
    }

    @Override
    public void doRecord(String savePath) {
        recordOnline(savePath);
    }

    private void recordOnline(String savePath) {
        int totalCnt = RecordConstant.RECORD_RETRY_CNT;
        for (int i = 0; i < 3; i++) {
            log.info("living stream record begin, savePath: {}, retry: {}/{}", savePath, i + 1, 3);
            FfmpegRecordCmd rfCmd = new FfmpegRecordCmd(buildCmd(savePath));
            // 执行录制，长时间
            rfCmd.execute(24 * 3600L);
            if (rfCmd.isExitNormal()) {
                log.info("living stream record end, savePath: {}", savePath);
                break;
            } else {
                log.error("living stream record fail, savePath: {}", savePath);
                continue;
            }
        }
    }

    private String buildCmd(String savePath) {
        // 计算分端视频开始index(默认从1开始)
        Integer segStartIndex = FileUtils.listFiles(new File(savePath), new String[]{"ts"}, false)
                .stream()
                .map(file -> VideoFileUtil.genIndex(file.getName()))
                .max(Integer::compare)
                .orElse(1);

        File segFile = new File(savePath, VideoFileUtil.SEG_FILE_NAME);
        List<String> commands = Lists.newArrayList(
                "ffmpeg",
                "-y",
                "-v verbose",
                "-loglevel error",
                "-hide_banner",
                "-i", "\"" + streamUrl + "\"",
                "-bufsize 10000k",
                "-c:v copy -c:a copy -c:s mov_text",
                "-map 0",
                "-f segment",
                "-segment_time 4",
                "-segment_start_number", String.valueOf(segStartIndex),
                "-segment_format mp4",
                "-movflags +faststart",
                "-reset_timestamps 1",
                "\"" + segFile.getAbsolutePath() + "\""
        );
        return StringUtils.join(commands, " ");
    }
}
