package com.sh.message.enums;

import java.util.Arrays;

/**
 * @Author caiwen
 * @Date 2023 07 29 16 17
 **/
public enum CorpWxEventTypeEnum {
    CHANGE_CONTACT("change_contact"),
    ;

    private String type;

    CorpWxEventTypeEnum(String type) {
        this.type = type;
    }

    public static CorpWxEventTypeEnum getEventType(String type) {
        return Arrays.stream(values())
                .filter(eventTypeEnum -> eventTypeEnum.type.equals(type))
                .findAny()
                .orElse(null);
    }
}
