package com.sh.engine.processor.plugin;

import com.alibaba.fastjson.JSON;
import com.sh.config.utils.VideoFileUtil;
import com.sh.engine.constant.ProcessPluginEnum;
import com.sh.engine.processor.plugin.valorant.ValorantDetectedMatch;
import com.sh.engine.processor.plugin.valorant.ValorantFullGameDetector;
import com.sh.engine.processor.plugin.valorant.ValorantFullGameExporter;
import com.sh.message.service.MsgSendService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 从直播录像中识别并导出每一局完整的无畏契约比赛。
 */
@Component
@Slf4j
public class ValorantFullGameCutPlugin implements VideoProcessPlugin {
    private static final Pattern SOURCE_VIDEO_PATTERN =
            Pattern.compile("(?i)^P\\d+\\.mp4$");

    private final ValorantFullGameDetector detector;
    private final ValorantFullGameExporter exporter;
    private final MsgSendService msgSendService;

    public ValorantFullGameCutPlugin(ValorantFullGameDetector detector,
                                     ValorantFullGameExporter exporter,
                                     MsgSendService msgSendService) {
        this.detector = detector;
        this.exporter = exporter;
        this.msgSendService = msgSendService;
    }

    @Override
    public String getPluginName() {
        return ProcessPluginEnum.VALORANT_FULL_GAME_CUT.getType();
    }

    @Override
    public boolean process(String recordPath) {
        List<File> sourceVideos = findSourceVideos(recordPath);
        if (sourceVideos.isEmpty()) {
            log.info("empty source mp4 video, skip valorant full game plugin, path: {}",
                    recordPath);
            return true;
        }

        List<ValorantDetectedMatch> matches = detector.detect(sourceVideos);
        log.info("valorant complete matches, path: {}, matches: {}",
                recordPath, JSON.toJSONString(matches));
        List<File> outputs = exporter.export(recordPath, matches);
        msgSendService.sendText("无畏契约整局剪辑完成：\n"
                + recordPath + "\n完整对局数量：" + outputs.size());
        return true;
    }

    @Override
    public int getMaxProcessParallel() {
        return 1;
    }

    private List<File> findSourceVideos(String recordPath) {
        return FileUtils.listFiles(new File(recordPath), new String[]{"mp4"}, false)
                .stream()
                .filter(file -> SOURCE_VIDEO_PATTERN.matcher(file.getName()).matches())
                .sorted(Comparator.comparingInt(VideoFileUtil::getVideoIndex))
                .collect(Collectors.toList());
    }
}
