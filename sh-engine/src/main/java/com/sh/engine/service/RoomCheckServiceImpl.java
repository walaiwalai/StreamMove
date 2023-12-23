package com.sh.engine.service;

import com.google.common.collect.Maps;
import com.sh.config.manager.ConfigFetcher;
import com.sh.config.model.config.StreamerInfo;
import com.sh.engine.StreamChannelTypeEnum;
import com.sh.engine.manager.RecordManager;
import com.sh.engine.manager.StatusManager;
import com.sh.engine.model.record.LivingStreamer;
import com.sh.engine.util.DateUtil;
import com.sh.engine.website.AbstractStreamerService;
import com.sh.engine.model.record.RecordTask;
import com.sh.engine.model.record.Recorder;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 检查房间是否在线，如果在线则开始录制
 * @author caiWen
 * @date 2023/1/23 9:26
 */
@Component
@Slf4j
public class RoomCheckServiceImpl implements RoomCheckService {
    @Resource
    StatusManager statusManager;
    @Resource
    RecordManager recordManager;
    @Resource
    ApplicationContext applicationContext;

    Map<StreamChannelTypeEnum, AbstractStreamerService> streamerServiceMap = Maps.newHashMap();
    @PostConstruct
    private void init() {
        Map<String, AbstractStreamerService> beansOfType = applicationContext.getBeansOfType(AbstractStreamerService.class);
        beansOfType.forEach((key, value) -> streamerServiceMap.put(value.getType(), value));
    }


    /**
     * 检查直播房间是否在线
     */
    @Override
    public void check() {
        List<StreamerInfo> streamerInfoList = ConfigFetcher.getStreamerInfoList();
        if (CollectionUtils.isEmpty(streamerInfoList)) {
            log.info("has no streamerInfo, will return");
            return;
        }

        for (StreamerInfo streamerInfo :streamerInfoList) {
            // 1. 检查streamer是否正在录制
            String name = streamerInfo.getName();

            // 2. 检查直播间是否开播
            String streamUrl = fetchRoomStreamUrl(streamerInfo);
            boolean isOnlineNow = StringUtils.isNotBlank(streamUrl);
            if (isOnlineNow) {
                SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
                // 2.1 直播间开播
                RecordTask recordTask = RecordTask.builder()
                        .streamUrl(streamUrl)
                        .recorderName(name)
                        .timeV(dateFormat.format(new Date()) + DateUtil.getCurDateDesc())
                        .build();
                processWhenRoomOnline(name, recordTask);
            } else {
                // 2.2 直播间没有开播
                processWhenRoomOffline(name);
            }

            log.info("check {} room finish", name);
        }
    }

    /**
     * 处理直播间开播
     * @param name
     * @param task
     */
    private void processWhenRoomOnline(String name, RecordTask task) {
        boolean isOnRecord = statusManager.isOnRecord(name);
        Recorder curRecorder = statusManager.getRecorderByStreamerName(name);
        boolean lastRoomOnline = statusManager.isRoomOnline(name);
        if (lastRoomOnline) {
            // 上次检测后认为房间在线
            if (isOnRecord) {
                if (curRecorder.getRecorderStat()) {
                    // 房间在线但是直播流断开，重启
                    log.info("下载流{} 断开，但直播间在线，重启", curRecorder.getRecordTask().getDirName());
                    recordManager.startRecord(curRecorder, task.getStreamUrl());
                } else {
                    log.info("{} is recording...", curRecorder.getRecordTask().getRecorderName());
                }
            } else {
                // 之前认为在线，但不存在 Recorder，这种情况不应该出现
                Recorder tmpRecorder = Recorder.initRecorder(task);
                recordManager.startRecord(tmpRecorder, task.getStreamUrl());
                statusManager.addRecorder(name, tmpRecorder);
            }
        } else {
            // 上次检测后认为房间不在线
            if (isOnRecord) {
                // 之前认为不在线，但存在 Recorder，这种情况不应该出现，可能未退出，应该将Record对应的线程杀死
                curRecorder.kill();
            } else {
                // 创建一个新的Recorder
                Recorder tmpRecorder = Recorder.initRecorder(task);
                recordManager.startRecord(tmpRecorder, task.getStreamUrl());
                statusManager.addRecorder(name, tmpRecorder);
            }
        }
        // 一些状态位
        statusManager.onlineRoom(name);
    }

    /**
     * 处理直播间下线的逻辑
     *
     * @param name
     */
    private void processWhenRoomOffline(String name) {
        boolean isLastOnRecord = statusManager.isOnRecord(name);
        Recorder curRecorder = statusManager.getRecorderByStreamerName(name);
        if (isLastOnRecord) {
            // 房间不在线，但仍在录制，先停止录制
            curRecorder.manualStopRecord();
            statusManager.deleteRoomPathStatus(curRecorder.getSavePath());
        }

        if (curRecorder != null) {
            // 如果之前再录制，删除Recoder
            log.info("Will delete Recorder: {}", curRecorder.getRecordTask().getRecorderName());
            statusManager.deleteRecorder(curRecorder.getRecordTask().getRecorderName());
        }

        statusManager.offlineRoom(name);
    }

    /**
     * 获取直播间的视频推送刘
     * @param streamerInfo
     * @return
     */
    private String fetchRoomStreamUrl(StreamerInfo streamerInfo) {
        StreamChannelTypeEnum channelEnum = StreamChannelTypeEnum.findChannelByUrl(streamerInfo.getRoomUrl());
        if (channelEnum == null) {
            log.error("roomUrl not match any platform, roomUrl: {}", streamerInfo.getRoomUrl());
            return null;
        }
        AbstractStreamerService streamerService = streamerServiceMap.get(channelEnum);
        if (streamerService == null) {
            log.error("streamerService is null, type: {}", channelEnum.getDesc());
            return null;
        }
        LivingStreamer livingStreamer = streamerService.isRoomOnline(streamerInfo);
        return Optional.ofNullable(livingStreamer).map(LivingStreamer::getRecordUrl).orElse(null);
    }
}
