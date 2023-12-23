package com.sh.engine;

/**
 * @Author caiwen
 * @Date 2023 12 18 23 18
 **/
public enum RecordStageEnum {
    CHECK_ROOM("CHECK_ROOM"),
    STREAM_RECORD("STREAM_RECORD"),
    VIDEO_UPLOAD("VIDEO_UPLOAD"),
    ERROR_HANDLE("ERROR_HANDLE"),
    END_HANDLE("END_HANDLE")
    ;
    private String code;

    RecordStageEnum(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }


}
