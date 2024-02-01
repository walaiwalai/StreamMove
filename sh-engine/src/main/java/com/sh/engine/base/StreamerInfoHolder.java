package com.sh.engine.base;

public class StreamerInfoHolder {
    /**
     * 保存录播人员
     */
    private static final ThreadLocal<Streamer> streamer = new ThreadLocal<Streamer>();

    public static void addName(String name) {
        if (streamer.get() == null) {
            streamer.set(new Streamer());
        }
        streamer.get().setName(name);
    }

    public static void addRecordPath(String path) {
        streamer.get().setRecordPath(path);
    }

    /**
     * 获取录播人名称
     */
    public static String getCurStreamerName() {
        if (streamer.get() == null) {
            return "";
        }
        return streamer.get().getName();
    }

    public static Streamer getCurStreamer() {
        return streamer.get();
    }

    /**
     * 移除当前用户对象
     */
    public static void clear(){
        streamer.remove();
    }
}
