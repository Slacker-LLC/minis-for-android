package com.openminis.app.speech

import android.content.Context
import java.io.File

/**
 * 语音纠错学习已随行内听写面板一并移除。旧版本留下的纠错库与同意开关不会再被读取，
 * 启动时一次性清掉，避免用户的口述/输入片段继续躺在应用目录里。
 *
 * 只按固定文件名删除 [filesDir] 下的纠错库（含 -wal/-shm）和它自己的偏好文件，
 * 不接受外部路径，也不碰其它文件。
 */
internal object LegacyVoiceCorrectionCleanup {

    internal const val DB_NAME = "voice-correction.db"
    internal const val PREFS_NAME = "voice_correction_prefs"

    fun run(context: Context) {
        val app = context.applicationContext
        deleteDatabaseFiles(app.filesDir)
        runCatching { app.deleteSharedPreferences(PREFS_NAME) }
    }

    /** 返回实际删除的文件数。目录里没有旧库时是空操作。 */
    internal fun deleteDatabaseFiles(filesDir: File): Int =
        listOf(DB_NAME, "$DB_NAME-wal", "$DB_NAME-shm", "$DB_NAME-journal")
            .count { name -> File(filesDir, name).let { it.isFile && it.delete() } }
}
