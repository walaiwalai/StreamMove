package com.sh.config.model.storage;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class FileStatusModelPostedWorkTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void savesPostedAidAcrossRetries() throws Exception {
        File recordDirectory = temporaryFolder.newFolder("record");
        FileStatusModel status = new FileStatusModel();
        assertNull(status.getBiliWebPostedAid());

        status.setBiliWebPostedAid(112861976201494L);
        status.writeSelfToFile(recordDirectory.getAbsolutePath());

        FileStatusModel reloaded = FileStatusModel.loadFromFile(recordDirectory.getAbsolutePath());
        assertEquals(Long.valueOf(112861976201494L), reloaded.getBiliWebPostedAid());
    }
}
