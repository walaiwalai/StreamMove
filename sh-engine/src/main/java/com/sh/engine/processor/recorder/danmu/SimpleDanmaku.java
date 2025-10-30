package com.sh.engine.processor.recorder.danmu;

/**
 * @Author : caiwen
 * @Date: 2025/10/26
 */
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class SimpleDanmaku implements Danmaku {
    /**
     * 发送时间（秒）
     */
    private long time;
    /**
     * 弹幕内容
     */
    private String text;
    /**
     * RGB颜色（如"FFFFFF"）
     */
    private String color;
}
