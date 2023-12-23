package com.sh.engine.model.bili.body;

/**
 * @author caiWen
 * @date 2023/2/26 17:28
 */

import com.sh.config.utils.VideoFileUtils;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.mime.MIME;
import org.apache.http.entity.mime.content.AbstractContentBody;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;

public class BlockStreamBody extends AbstractContentBody {
    private File targetFile;
    private long blockStart;
    private int blockSize;

    public BlockStreamBody(long blockStart, int blockSize, File targetFile) {
        super(ContentType.APPLICATION_OCTET_STREAM);
        this.blockStart = blockStart;
        this.blockSize = blockSize;
        this.targetFile = targetFile;
    }

    @Override
    public String getFilename() {
        return this.targetFile.getName();
    }

    /**
     * 此方法每调用一次,只输出文件中的某一个块的数据
     */
    @Override
    public void writeTo(OutputStream out) throws IOException {
        byte[] bytes = VideoFileUtils.fetchBlock(this.targetFile, this.blockStart, this.blockSize);
        out.write(bytes);
    }

    @Override
    public String getTransferEncoding() {
        return MIME.ENC_BINARY;
    }

    @Override
    public long getContentLength() {
        return this.blockSize;
    }
}
