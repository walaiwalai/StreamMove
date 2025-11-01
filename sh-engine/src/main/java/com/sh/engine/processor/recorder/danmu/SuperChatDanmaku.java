package com.sh.engine.processor.recorder.danmu;

import lombok.Data;

import java.util.Map;

/**
 * 超级弹幕实体类，封装超级弹幕的核心信息
 */
public class SuperChatDanmaku extends SimpleDanmaku {
    // 超级弹幕价格
    private float price;
    // 价格单位（如"元"）
    private String priceUnit;
    // 展示时长（秒）
    private int duration;

    public SuperChatDanmaku(Float time, Float timestamp, String content, String text, Map<String, Object> extra,
                            float price, String priceUnit, int duration, String name) {
        super(time, timestamp, "superchat", name, null, content, text, extra);

        this.price = price;
        this.priceUnit = priceUnit;
        this.duration = duration;
    }

    public SuperChatDanmaku(float time, String name, String content, float price, String priceUnit, int duration) {
        this(time, null, content, null, null, price, priceUnit, duration, name);
    }

    public float getPrice() {
        return price;
    }

    public String getPriceUnit() {
        return priceUnit;
    }

    public int getDuration() {
        return duration;
    }
}
