package com.sh.engine.base;

import com.sh.engine.StreamChannelTypeEnum;

import java.util.List;

public class StreamerInfoHolder {
    /**
     * 保存录播人员
     */
    private static final ThreadLocal<Streamer> streamerLocal = new ThreadLocal<Streamer>();

    public static void addStreamer(Streamer streamer) {
        streamerLocal.set(streamer);
    }

    /**
     * 获取录播人名称
     */
    public static String getCurStreamerName() {
        return streamerLocal.get().getName();
    }
    public static StreamChannelTypeEnum getCurChannel() {
        return streamerLocal.get().getChannel();
    }

    public static List<String> getCurRecordPaths() {
        return streamerLocal.get().getRecordPaths();
    }

    public static void addRecordPath(String recordPath) {
        List<String> recordPaths = streamerLocal.get().getRecordPaths();
        if (recordPaths.contains(recordPath)) {
            return;
        }
        streamerLocal.get().getRecordPaths().add(recordPath);
    }

    public void addChannel( StreamChannelTypeEnum channel ) {
        streamerLocal.get().setChannel(channel);
    }

    /**
     * 移除当前用户对象
     */
    public static void clear() {
        streamerLocal.remove();
    }
}
