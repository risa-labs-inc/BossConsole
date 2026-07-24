package ai.rever.boss.components.plugin.providers

import ai.rever.boss.plugin.api.DialogButton
import ai.rever.boss.plugin.api.DialogChoice
import ai.rever.boss.plugin.api.DialogChoiceItem
import ai.rever.boss.plugin.api.GenericDialogProvider
import ai.rever.boss.plugin.api.ProgressDialogHandle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Desktop implementation of GenericDialogProvider factory.
 */
actual fun createGenericDialogProvider(): GenericDialogProvider? = GenericDialogProviderImpl.getInstance()

/**
 * State for a pending dialog request.
 */
sealed interface DialogRequest {
    val id: String

    data class TextInput(
        override val id: String,
        val title: String,
        val message: String?,
        val initialValue: String,
        val placeholder: String,
        val validation: ((String) -> String?)?,
        val result: CompletableDeferred<String?>,
    ) : DialogRequest

    data class Confirmation(
        override val id: String,
        val title: String,
        val message: String,
        val confirmText: String,
        val cancelText: String,
        val isDestructive: Boolean,
        val result: CompletableDeferred<Boolean>,
    ) : DialogRequest

    data class SingleChoice(
        override val id: String,
        val title: String,
        val message: String?,
        val choices: List<DialogChoice>,
        val selectedIndex: Int,
        val result: CompletableDeferred<DialogChoice?>,
    ) : DialogRequest

    data class MultiChoice(
        override val id: String,
        val title: String,
        val message: String?,
        val choices: List<DialogChoiceItem>,
        val result: CompletableDeferred<List<DialogChoiceItem>?>,
    ) : DialogRequest

    data class Alert(
        override val id: String,
        val title: String,
        val message: String,
        val buttonText: String,
        val result: CompletableDeferred<Unit>,
    ) : DialogRequest

    data class ThreeButton(
        override val id: String,
        val title: String,
        val message: String,
        val positiveText: String,
        val negativeText: String,
        val neutralText: String,
        val result: CompletableDeferred<DialogButton>,
    ) : DialogRequest

    data class Progress(
        override val id: String,
        val title: String,
        val message: String,
        val isIndeterminate: Boolean,
        val cancellable: Boolean,
        val handle: ProgressDialogHandleImpl,
    ) : DialogRequest
}

/**
 * Desktop implementation of GenericDialogProvider.
 *
 * This uses a state-based approach where dialog requests are queued
 * and a dialog host composable renders them. Use GenericDialogHost
 * in your UI to display the dialogs.
 */
class GenericDialogProviderImpl private constructor() : GenericDialogProvider {
    companion object {
        @Volatile
        private var instance: GenericDialogProviderImpl? = null

        fun getInstance(): GenericDialogProviderImpl =
            instance ?: synchronized(this) {
                instance ?: GenericDialogProviderImpl().also { instance = it }
            }
    }

    private val _currentDialog = MutableStateFlow<DialogRequest?>(null)

    /**
     * Observable state for the current dialog request.
     * UI should observe this and render the appropriate dialog.
     */
    val currentDialog: StateFlow<DialogRequest?> = _currentDialog.asStateFlow()

    override suspend fun showTextInputDialog(
        title: String,
        message: String?,
        initialValue: String,
        placeholder: String,
        validation: ((String) -> String?)?,
    ): String? {
        val result = CompletableDeferred<String?>()
        val request =
            DialogRequest.TextInput(
                id = UUID.randomUUID().toString(),
                title = title,
                message = message,
                initialValue = initialValue,
                placeholder = placeholder,
                validation = validation,
                result = result,
            )
        _currentDialog.value = request
        return result.await().also { _currentDialog.value = null }
    }

    override suspend fun showConfirmationDialog(
        title: String,
        message: String,
        confirmText: String,
        cancelText: String,
        isDestructive: Boolean,
    ): Boolean {
        val result = CompletableDeferred<Boolean>()
        val request =
            DialogRequest.Confirmation(
                id = UUID.randomUUID().toString(),
                title = title,
                message = message,
                confirmText = confirmText,
                cancelText = cancelText,
                isDestructive = isDestructive,
                result = result,
            )
        _currentDialog.value = request
        return result.await().also { _currentDialog.value = null }
    }

    override suspend fun showChoiceDialog(
        title: String,
        message: String?,
        choices: List<DialogChoice>,
        selectedIndex: Int,
    ): DialogChoice? {
        val result = CompletableDeferred<DialogChoice?>()
        val request =
            DialogRequest.SingleChoice(
                id = UUID.randomUUID().toString(),
                title = title,
                message = message,
                choices = choices,
                selectedIndex = selectedIndex,
                result = result,
            )
        _currentDialog.value = request
        return result.await().also { _currentDialog.value = null }
    }

    override suspend fun showMultiChoiceDialog(
        title: String,
        message: String?,
        choices: List<DialogChoiceItem>,
    ): List<DialogChoiceItem>? {
        val result = CompletableDeferred<List<DialogChoiceItem>?>()
        val request =
            DialogRequest.MultiChoice(
                id = UUID.randomUUID().toString(),
                title = title,
                message = message,
                choices = choices,
                result = result,
            )
        _currentDialog.value = request
        return result.await().also { _currentDialog.value = null }
    }

    override suspend fun showAlertDialog(
        title: String,
        message: String,
        buttonText: String,
    ) {
        val result = CompletableDeferred<Unit>()
        val request =
            DialogRequest.Alert(
                id = UUID.randomUUID().toString(),
                title = title,
                message = message,
                buttonText = buttonText,
                result = result,
            )
        _currentDialog.value = request
        result.await()
        _currentDialog.value = null
    }

    override suspend fun showThreeButtonDialog(
        title: String,
        message: String,
        positiveText: String,
        negativeText: String,
        neutralText: String,
    ): DialogButton {
        val result = CompletableDeferred<DialogButton>()
        val request =
            DialogRequest.ThreeButton(
                id = UUID.randomUUID().toString(),
                title = title,
                message = message,
                positiveText = positiveText,
                negativeText = negativeText,
                neutralText = neutralText,
                result = result,
            )
        _currentDialog.value = request
        return result.await().also { _currentDialog.value = null }
    }

    override fun showProgressDialog(
        title: String,
        message: String,
        isIndeterminate: Boolean,
        cancellable: Boolean,
    ): ProgressDialogHandle {
        val handle =
            ProgressDialogHandleImpl(
                onDismiss = { _currentDialog.value = null },
            )
        val request =
            DialogRequest.Progress(
                id = UUID.randomUUID().toString(),
                title = title,
                message = message,
                isIndeterminate = isIndeterminate,
                cancellable = cancellable,
                handle = handle,
            )
        _currentDialog.value = request
        return handle
    }

    /**
     * Dismiss the current dialog.
     * Used by the dialog host when the user dismisses.
     */
    fun dismissCurrent() {
        _currentDialog.value = null
    }
}

/**
 * Implementation of ProgressDialogHandle for desktop.
 */
class ProgressDialogHandleImpl(
    private val onDismiss: () -> Unit,
) : ProgressDialogHandle {
    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    private val _message = MutableStateFlow("")
    val message: StateFlow<String> = _message.asStateFlow()

    private val _isCancelled = AtomicBoolean(false)
    private var onCancelCallback: (() -> Unit)? = null

    override fun updateProgress(progress: Float) {
        _progress.value = progress.coerceIn(0f, 1f)
    }

    override fun updateMessage(message: String) {
        _message.value = message
    }

    override fun dismiss() {
        onDismiss()
    }

    override fun isCancelled(): Boolean = _isCancelled.get()

    override fun setOnCancelListener(callback: () -> Unit) {
        onCancelCallback = callback
    }

    /**
     * Called by the dialog host when the user cancels.
     */
    fun cancel() {
        _isCancelled.set(true)
        onCancelCallback?.invoke()
    }
}
