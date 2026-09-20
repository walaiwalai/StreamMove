package com.sh.engine.processor.uploader;

import com.sh.engine.constant.RecordConstant;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

public class UploaderHighlightAssetsTest {
    @Test
    public void shouldPreferGeneratedHighlightTitleAndCover() throws Exception {
        Path recordDirectory = Files.createTempDirectory("highlight-assets-");
        try {
            Path cover = Files.createFile(recordDirectory.resolve(
                    RecordConstant.HIGHLIGHT_THUMBNAIL_FILE_NAME));
            Files.write(recordDirectory.resolve(RecordConstant.HIGHLIGHT_TITLE_FILE_NAME),
                    "舞台灯光突然亮起".getBytes(StandardCharsets.UTF_8));
            Uploader uploader = mock(Uploader.class, CALLS_REAL_METHODS);
            Method findCover = Uploader.class.getDeclaredMethod(
                    "findHighlightCover", String.class);
            findCover.setAccessible(true);
            Method resolveTitle = Uploader.class.getDeclaredMethod(
                    "resolveHighlightTitle", String.class, String.class);
            resolveTitle.setAccessible(true);

            Assert.assertEquals(cover.toFile(),
                    findCover.invoke(uploader, recordDirectory.toString()));
            Assert.assertEquals("舞台灯光突然亮起",
                    resolveTitle.invoke(uploader, recordDirectory.toString(), "录播标题"));
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(recordDirectory)) {
                paths.sorted(Comparator.reverseOrder())
                        .forEach(path -> path.toFile().delete());
            }
        }
    }

}
