package ai.rever.boss.plugin.sandbox.notification

import java.util.UUID

/**
 * Controller for displaying toast/snackbar messages to the user.
 *
 * Implementations should integrate with the application's UI framework
 * to show transient notifications.
 */
interface ToastController {
    /**
     * Show a toast message.
     *
     * @param message The message to display
     */
    fun show(message: ToastMessage)

    /**
     * Dismiss a specific toast by its ID.
     *
     * @param id The unique ID of the toast to dismiss
     */
    fun dismiss(id: String)

    /**
     * Dismiss all currently showing toasts.
     */
    fun dismissAll()
}

/**
 * A toast message to display to the user.
 *
 * @property id Unique identifier for this toast
 * @property type The visual style/severity of the toast
 * @property title Short title for the toast
 * @property message Detailed message content
 * @property action Optional action button
 * @property duration How long the toast should be visible
 */
data class ToastMessage(
    val id: String = UUID.randomUUID().toString(),
    val type: ToastType,
    val title: String,
    val message: String,
    val action: ToastAction? = null,
    val duration: ToastDuration = ToastDuration.SHORT,
)

/**
 * Visual type/severity of a toast message.
 */
enum class ToastType {
    /** Informational message (neutral color) */
    INFO,

    /** Success message (green/positive color) */
    SUCCESS,

    /** Warning message (yellow/caution color) */
    WARNING,

    /** Error message (red/negative color) */
    ERROR,
}

/**
 * How long a toast should remain visible.
 */
enum class ToastDuration {
    /** Short duration (~3 seconds) */
    SHORT,

    /** Longer duration (~6 seconds) */
    LONG,

    /** Stays visible until dismissed by user or programmatically */
    INDEFINITE,
}

/**
 * An optional action button on a toast.
 *
 * @property label The button text
 * @property onClick Callback when the button is clicked
 */
data class ToastAction(
    val label: String,
    val onClick: () -> Unit,
)
