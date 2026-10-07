package ai.rever.boss.plugin.browser

import ai.rever.boss.sharing.AppBrowserMenuDispatch
import com.teamdev.jxbrowser.frame.Frame
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicLong

/** Per-handle authority; navigation or callback replacement revokes previously issued tokens. */
internal class BrowserMenuContextAuthority {
    private val generation = AtomicLong()

    fun invalidate() {
        generation.incrementAndGet()
    }

    fun snapshot(): Long = generation.get()

    fun capture(
        frame: Frame?,
        capturedGeneration: Long = snapshot(),
        remoteClick: AppBrowserMenuDispatch.Click? = null,
    ): BrowserMenuContext {
        val token = BrowserMenuContextImpl(WeakReference(frame), this, capturedGeneration)
        return if (remoteClick == null) token else RemoteBrowserMenuContext(token, remoteClick)
    }

    fun resolve(context: BrowserMenuContext): Frame? {
        val token =
            when (context) {
                is BrowserMenuContextImpl -> context
                is RemoteBrowserMenuContext -> context.token
                else -> null
            } ?: return null
        val frame = token.frameRef.get()
        return frame.takeIf { token.owner === this && token.generation == generation.get() }
    }
}

private class BrowserMenuContextImpl(
    val frameRef: WeakReference<Frame>,
    val owner: BrowserMenuContextAuthority,
    val generation: Long,
) : BrowserMenuContext

/**
 * Optional AWT mouse-event interop on the opaque frame token preserves the published API ABI.
 * Updated plugins can anchor a remote menu and own its popup without moving the system pointer;
 * older plugins and hosts continue using their existing local menu path.
 */
private class RemoteBrowserMenuContext(
    val token: BrowserMenuContextImpl,
    click: AppBrowserMenuDispatch.Click,
) : java.awt.event.MouseEvent(
        click.event.component,
        click.event.id,
        click.event.`when`,
        click.event.modifiersEx,
        click.event.x,
        click.event.y,
        click.event.xOnScreen,
        click.event.yOnScreen,
        click.event.clickCount,
        true,
        click.event.button,
    ),
    BrowserMenuContext

internal fun BrowserContextMenuInfo.withFormField(form: FormFieldInfo?): BrowserContextMenuInfo =
    BrowserContextMenuInfo(
        linkUrl,
        selectedText,
        isEditable,
        hasVideo,
        hasImage,
        imageUrl,
        pageUrl,
        pageTitle,
        form,
        menuContext,
    )
