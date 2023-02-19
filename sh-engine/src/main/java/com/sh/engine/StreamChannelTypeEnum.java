package com.sh.engine;

import org.apache.commons.lang3.StringUtils;

/**
 * @author caiWen
 * @date 2023/1/23 13:41
 */
public enum StreamChannelTypeEnum {
    /**
     * 虎牙
     */
    HUYA(1, "虎牙", "www.huya.com"),

    /**
     * 斗鱼
     */
    DOUYU(2, "斗鱼", "www.douyu.com"),

    /**
     * afreecatv
     */
    AFREECA_TV(3, "afreecatv", "play.afreecatv.com"),
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
