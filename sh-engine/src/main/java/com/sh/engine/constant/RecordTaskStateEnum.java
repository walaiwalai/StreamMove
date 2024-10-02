package com.sh.engine.constant;

/**
 * @Author caiwen
 * @Date 2023 12 18 22 56
 **/
public enum RecordTaskStateEnum {
    INIT(0),

    ROOM_CHECK_FINISH(1),

    STREAM_RECORD_FINISH(2),

    VIDEO_PROCESS_FINISH(3),

    VIDEO_UPLOAD_FINISH(4),

    ERROR(5),

    END(6),
    ;

    private int code;

    RecordTaskStateEnum(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    public boolean isFinishedState() {
        return this == END;
    }
}
