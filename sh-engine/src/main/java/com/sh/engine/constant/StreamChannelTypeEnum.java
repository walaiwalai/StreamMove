package com.sh.engine.constant;

import org.apache.commons.lang3.StringUtils;

/**
 * @author caiWen
 * @date 2023/1/23 13:41
 */
public enum StreamChannelTypeEnum {
    /**
     * 虎牙
     */
    HUYA(1, "虎牙", "huya.com"),

    /**
     * 斗鱼
     */
    DOUYU(2, "斗鱼", "douyu.com"),

    /**
     * afreecatv
     */
    AFREECA_TV(3, "afreecatv", "afreecatv.com"),

    /**
     * bilibili
     */
    BILI(4, "b站", "live.bilibili.com"),

    /**
     * twitch
     */
    TWITCH(5, "twitch", "twitch.tv"),

    /**
     * chzzk
     */
    CHZZK(6, "chzzk", "chzzk.naver.com"),

    /**
     * minio
     */
    MINIO(7, "minio", "minio"),

    /**
     * 抖音
     */
    DOU_YIN(8, "抖音", "live.douyin.com"),

    XHS(9, "小红书", "xiaohongshu.com"),
    ;

    private int type;
    private String desc;
    private String regex;

    StreamChannelTypeEnum(int type, String desc, String regex) {
        this.type = type;
        this.desc = desc;
        this.regex = regex;
    }

    public int getType() {
        return type;
    }

    public void setType(int type) {
        this.type = type;
    }

    public String getDesc() {
        return desc;
    }

    public void setDesc(String desc) {
        this.desc = desc;
    }

    public String getRegex() {
        return regex;
    }

    public void setRegex(String regex) {
        this.regex = regex;
    }

    /**
     * 根据url找到对应的平台
     *
     * @param url
     * @return
     */
    public static StreamChannelTypeEnum findChannelByUrl(String url) {
        if (StringUtils.isBlank(url)) {
            return null;
        }
        for (StreamChannelTypeEnum channelEnum : StreamChannelTypeEnum.values()) {
            if (url.contains(channelEnum.getRegex())) {
                return channelEnum;
            }
        }
        return null;
    }
}
