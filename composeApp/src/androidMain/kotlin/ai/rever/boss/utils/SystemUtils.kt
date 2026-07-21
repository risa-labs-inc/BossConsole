package ai.rever.boss.utils

import android.os.Environment

actual object SystemUtils {
    actual fun getUserHome(): String = Environment.getExternalStorageDirectory().absolutePath
    
    actual fun getCurrentDirectory(): String = getUserHome()
    
    actual fun getDefaultProjectPath(): String = getUserHome()
}
