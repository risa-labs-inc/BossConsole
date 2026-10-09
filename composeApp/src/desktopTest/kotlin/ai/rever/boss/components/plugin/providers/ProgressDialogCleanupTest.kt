package ai.rever.boss.components.plugin.providers

import ai.rever.boss.plugin.api.ProgressDialogHandle
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ProgressDialogCleanupTest {
    @AfterTest
    fun tearDown() {
        GenericDialogProviderImpl.getInstance().dismissCurrent()
    }

    @Test
    fun `cancellation can open a replacement without old dismissal closing it`() {
        val provider = GenericDialogProviderImpl.getInstance()
        val old = assertIs<ProgressDialogHandleImpl>(provider.showProgressDialog("old", "", false, true))
        var replacement: ProgressDialogHandle? = null
        old.setOnCancelListener {
            replacement = provider.showProgressDialog("replacement", "", false, true)
        }

        old.cancelAndDismiss()

        assertSame(replacement, assertIs<DialogRequest.Progress>(provider.currentDialog.value).handle)
        assertTrue(old.isCancelled())
    }

    @Test
    fun `stale progress dismissal leaves its replacement open`() {
        val provider = GenericDialogProviderImpl.getInstance()
        val old = provider.showProgressDialog("old", "", false, true)
        val replacement = provider.showProgressDialog("replacement", "", false, true)

        old.dismiss()

        assertSame(replacement, assertIs<DialogRequest.Progress>(provider.currentDialog.value).handle)
        replacement.dismiss()
        assertEquals(null, provider.currentDialog.value)
    }

    @Test
    fun `a failing plugin cancellation still dismisses the dialog once`() {
        val events = mutableListOf<String>()
        val handle = ProgressDialogHandleImpl { events.add("dismiss") }
        handle.setOnCancelListener {
            events.add("cancel")
            throw LinkageError("plugin callback failed")
        }

        handle.cancelAndDismiss()
        handle.cancelAndDismiss()
        handle.dismiss()

        assertTrue(handle.isCancelled())
        assertEquals(listOf("cancel", "dismiss"), events)
    }

    @Test
    fun `a failing host dismissal cannot repeat the plugin cancellation`() {
        var cancellations = 0
        var dismissals = 0
        val handle =
            ProgressDialogHandleImpl {
                dismissals++
                error("dialog dismissal failed")
            }
        handle.setOnCancelListener { cancellations++ }

        handle.cancelAndDismiss()
        handle.cancelAndDismiss()

        assertEquals(1, cancellations)
        assertEquals(1, dismissals)
    }
}
