package ai.rever.boss.startup

import ai.rever.boss.startup.ChromiumBootstrap.AfterDownload
import ai.rever.boss.startup.ChromiumBootstrap.afterDownloadAction
import kotlin.test.Test
import kotlin.test.assertEquals

class ChromiumBootstrapAfterDownloadTest {
    @Test
    fun relaunchesWhenThePackagedBundleCanBeReopened() {
        assertEquals(AfterDownload.Relaunch, afterDownloadAction(canRelaunch = true))
    }

    @Test
    fun bootsInProcessWhenItCannot() {
        assertEquals(AfterDownload.BootInProcess, afterDownloadAction(canRelaunch = false))
    }
}
