package com.sh.engine.processor.recorder.danmu;

import lombok.Data;

import java.util.HashMap;
import java.util.Map;

/**
 * 基础弹幕类，封装通用弹幕属性
 */
@Data
public class SimpleDanmaku implements Danmaku {
    /**
     * 相对时间（秒）
     */
    private float time;

    /**
     * 绝对时间戳（秒）
     */
    private float timestamp;
    /**
     * 弹幕类型（如'gift'/'superchat'/'entry'/'other'）
     */
    private String dtype;
    /**
     * 发送者名称
     */
    private String uname;
    /**
     * 弹幕颜色（6位16进制，默认白色）
     */
    private String color = "ffffff";
    /**
     * 原始内容（可能包含非文本信息）
     */
    private String content;
    /**
     * 最终显示的文本
     */
    private String text;
    /**
     * 存储额外参数
     */
    private Map<String, Object> extra = new HashMap<>();


    public SimpleDanmaku(Float time, Float timestamp, String dtype, String uname, String color,
                         String content, String text, Map<String, Object> extra) {
        // 处理相对时间
        this.time = time != null ? time : 0.0f;

        // 处理绝对时间戳（默认当前时间）
        if (timestamp != null) {
            this.timestamp = timestamp;
        } else {
            this.timestamp = System.currentTimeMillis() / 1000.0f;
        }

        this.dtype = dtype;
        this.uname = uname;
        this.color = color != null ? color : "ffffff";
        this.content = content;

        // 文本默认使用content
        this.text = text != null ? text : content;

        // 处理额外参数
        if (extra != null) {
            this.extra.putAll(extra);
        }
    }

    public SimpleDanmaku(float time, String content, String color) {
        this(time, null, null, null, color, content, null, null);
    }
    
    /**
     * 将弹幕转换为一行文本格式
     * 格式: 时间__SEP__内容__SEP__颜色
     * @return 弹幕的文本表示
     */
    public String toLine() {
        return String.format("%.3f__SEP__%s__SEP__%s", time, content, color);
    }
    
    /**
     * 从一行文本还原弹幕对象
     * @param line 文本行
     * @return 弹幕对象
     */
    public static SimpleDanmaku fromLine(String line) {
        if (line == null || line.isEmpty()) {
            return null;
        }
        String[] parts = line.split("__SEP__");
        if (parts.length >= 3) {
            float time = Float.parseFloat(parts[0]);
            String content = parts[1];
            String color = parts[2];
            return new SimpleDanmaku(time, content, color);
        }
        return null;
    }
}