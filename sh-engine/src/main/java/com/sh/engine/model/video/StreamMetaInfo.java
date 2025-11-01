package com.sh.engine.model.video;

import lombok.Data;

@Data
public class StreamMetaInfo {
    private Integer width;
    private Integer height;

    public boolean isValid() {
        return width != null && height != null;
    }
}
