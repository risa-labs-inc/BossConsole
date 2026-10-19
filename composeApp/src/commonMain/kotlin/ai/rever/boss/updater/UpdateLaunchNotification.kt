package ai.rever.boss.updater

import ai.rever.boss.utils.Version

/** Claims the notification once per installation, after an upgraded version actually launches. */
internal expect suspend fun takeCompletedUpdateNotification(): Version?
