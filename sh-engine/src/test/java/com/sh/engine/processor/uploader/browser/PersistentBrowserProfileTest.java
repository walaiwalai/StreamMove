package com.sh.engine.processor.uploader.browser;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PersistentBrowserProfileTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void createsProfileBesideStorageStateAndMarksItReady() throws Exception {
        File accountDirectory = temporaryFolder.newFolder("account");
        File storageState = new File(accountDirectory, "platform-cookies.json");

        PersistentBrowserProfile profile = new PersistentBrowserProfile(storageState,
                "platform-browser-profile", "测试平台");

        assertEquals(new File(accountDirectory, "platform-browser-profile").getCanonicalFile(),
                profile.getProfilePath().toFile().getCanonicalFile());
        assertEquals(new File(accountDirectory, "login-qr.png").getCanonicalFile(),
                profile.resolveAccountFile("login-qr.png").getCanonicalFile());

        Files.write(storageState.toPath(), "{\"cookies\":[],\"origins\":[]}".getBytes("UTF-8"));
        profile.markReady();
        File marker = new File(profile.getProfilePath().toFile(),
                ".streamer-record-profile-ready");
        assertTrue(marker.isFile());
        assertEquals(64, Files.readAllLines(marker.toPath()).get(0).length());
    }
}
