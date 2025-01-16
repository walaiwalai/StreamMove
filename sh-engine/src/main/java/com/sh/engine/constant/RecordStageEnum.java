package com.sh.engine.constant;

/**
 * @Author caiwen
 * @Date 2023 12 18 23 18
 **/
public enum RecordStageEnum {
    STATUS_CHECK("STATUS_CHECK"),
    ROOM_CHECK("ROOM_CHECK"),
    STREAM_RECORD("STREAM_RECORD"),
    VIDEO_UPLOAD("VIDEO_UPLOAD"),
    VIDEO_PROCESS("VIDEO_PROCESS"),
    ERROR_HANDLE("ERROR_HANDLE"),
    END_HANDLE("END_HANDLE");
    private String code;

    RecordStageEnum(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }


}
