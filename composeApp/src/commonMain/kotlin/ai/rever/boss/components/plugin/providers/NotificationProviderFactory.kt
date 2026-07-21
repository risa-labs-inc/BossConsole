package ai.rever.boss.components.plugin.providers

import ai.rever.boss.plugin.api.NotificationProvider
import ai.rever.boss.plugin.sandbox.notification.ToastController

/**
 * Factory function to create platform-specific NotificationProvider.
 * Desktop implementation delegates to the existing PluginToastState.
 *
 * @param toastController The toast controller to delegate to
 * @return NotificationProvider implementation
 */
expect fun createNotificationProvider(toastController: ToastController): NotificationProvider
