package com.sh.engine.service.impl.oss;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.OSSObject;
import com.sh.config.exception.StreamerRecordException;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class AliyunOssUploadServiceImplTest {
    private final AliyunOssUploadServiceImpl service = new AliyunOssUploadServiceImpl();
    private final OSS ossClient = mock(OSS.class);

    @Before
    public void setUp() throws Exception {
        Field clientField = service.getClass().getDeclaredField("ossClient");
        clientField.setAccessible(true);
        clientField.set(service, ossClient);
        Field bucketField = service.getClass().getDeclaredField("bucketName");
        bucketField.setAccessible(true);
        bucketField.set(service, "bucket");
    }

    @Test
    public void readsOssImageByObjectKey() {
        OSSObject image = mock(OSSObject.class);
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentType("application/octet-stream");
        when(image.getObjectMetadata()).thenReturn(metadata);
        when(image.getObjectContent()).thenReturn(new ByteArrayInputStream(new byte[]{1, 2, 3}));
        when(ossClient.getObject("bucket", "covers/season.png")).thenReturn(image);

        assertEquals("data:image/png;base64,AQID", service.getImageDataUrl("covers/season.png"));
    }

    @Test(expected = StreamerRecordException.class)
    public void rejectsNonImageObject() {
        OSSObject object = mock(OSSObject.class);
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentType("text/plain");
        when(object.getObjectMetadata()).thenReturn(metadata);
        when(ossClient.getObject("bucket", "covers/season.txt")).thenReturn(object);

        service.getImageDataUrl("covers/season.txt");
    }
}
