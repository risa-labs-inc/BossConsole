package ai.rever.boss.pet

import ai.rever.boss.updater.UpdateState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BossPetUpdateStateTest {
    @Test
    fun `a routine update check cannot strand the pet working`() {
        val controller = BossPetController()
        reportPetUpdateState(controller, UpdateState.CheckingForUpdates)
        assertEquals(BossPetMood.Working(1), controller.mood.value)
        reportPetUpdateState(controller, UpdateState.UpToDate)
        assertEquals(BossPetMood.Idle, controller.mood.value)
    }

    @Test
    fun `quiet updater states cannot acknowledge another task failure`() {
        val controller = BossPetController()
        controller.taskFailed("build", "Build failed")
        reportPetUpdateState(controller, UpdateState.CheckingForUpdates)
        reportPetUpdateState(controller, UpdateState.Idle)
        assertEquals(BossPetMood.Failed("Build failed", "build"), controller.mood.value)
        controller.dismissAnnouncement()
        assertEquals(BossPetMood.Idle, controller.mood.value)
    }

    @Test
    fun `download progress counts once and completion clears activity`() {
        val controller = BossPetController()
        reportPetUpdateState(controller, UpdateState.Downloading(0.1f))
        reportPetUpdateState(controller, UpdateState.Downloading(0.5f))
        assertEquals(BossPetMood.Working(1), controller.mood.value)
        reportPetUpdateState(controller, UpdateState.ReadyToInstall("/tmp/update"))
        controller.dismissAnnouncement()
        assertEquals(BossPetMood.Idle, controller.mood.value)
    }

    @Test
    fun `an install failure survives even if the state flow skipped installing`() {
        val controller = BossPetController()
        reportPetUpdateState(controller, UpdateState.ReadyToInstall("/tmp/update"))
        reportPetUpdateState(controller, UpdateState.Error("Install failed"))
        controller.onIdleTimeout()
        assertEquals(BossPetMood.Failed("Update: Install failed", "app-update", 1), controller.mood.value)
        controller.onIdleTimeout()
        assertEquals(BossPetMood.Failed("Update: Install failed", "app-update", 1), controller.mood.value)
    }

    @Test
    fun `restart notice survives even if the state flow skipped installing`() {
        val controller = BossPetController()
        reportPetUpdateState(controller, UpdateState.ReadyToInstall("/tmp/update"))
        reportPetUpdateState(controller, UpdateState.RestartRequired)
        controller.onIdleTimeout()
        assertEquals(
            BossPetMood.Completed("Restart BOSS", "app-update", 1, requiresAcknowledgement = true),
            controller.mood.value,
        )
    }

    @Test
    fun `discarding a ready update retracts only that success`() {
        val controller = BossPetController()
        controller.taskFailed("build", "Build failed")
        reportPetUpdateState(controller, UpdateState.ReadyToInstall("/tmp/update"))
        reportPetUpdateState(controller, UpdateState.Idle)
        assertEquals(BossPetMood.Failed("Build failed", "build"), controller.mood.value)
        controller.dismissAnnouncement()
        assertEquals(BossPetMood.Idle, controller.mood.value)
    }

    @Test
    fun `distinct updater failures remain distinct and repeated failures are counted`() {
        val controller = BossPetController()
        reportPetUpdateState(controller, UpdateState.Error("Download failed"))
        reportPetUpdateState(controller, UpdateState.Error("Install failed"))
        reportPetUpdateState(controller, UpdateState.Error("Download failed"))
        assertEquals(
            BossPetMood.Failed("Update: Download failed", "app-update", occurrences = 2),
            controller.mood.value,
        )
        controller.dismissAnnouncement()
        assertEquals(BossPetMood.Failed("Update: Install failed", "app-update", 1), controller.mood.value)
    }

    @Test
    fun `updater failure details are sanitized and bounded before showing outside BOSS`() {
        val label =
            petUpdateFailureLabel("Download failed at https://private.example/file?token=super-secret\nTry again")
        assertFalse(label.contains("super-secret"))
        assertFalse(label.contains("private.example"))
        assertFalse(label.contains('\n'))
        assertTrue(label.contains("Download failed"))
        assertTrue(petUpdateFailureLabel("x".repeat(1_000)).length <= 88)
    }
}
