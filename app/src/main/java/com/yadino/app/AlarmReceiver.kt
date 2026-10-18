
package com.yadino.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * دریافت آلارم زمان‌بندی‌شدهٔ یادینو.
 *
 * این کلاس به‌تنهایی رابط تنظیم آلارم نیست؛
 * زمان‌بندی، اعلان و توقف صدا در فایل‌های هماهنگ بعدی تکمیل می‌شوند.
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val noteId = intent.getStringExtra(EXTRA_NOTE_ID)
        val noteTitle = intent.getStringExtra(EXTRA_NOTE_TITLE)
            ?: "یادآوری یادینو"

        if (noteId.isNullOrBlank()) {
            Log.w(TAG, "Alarm received without a note ID")
            return
        }

        Log.i(TAG, "Alarm received for note: $noteId")

        // اجرای نمایش یادآوری به سرویس آلارم واگذار می‌شود.
        // سرویس در فایل هماهنگ بعدی اضافه خواهد شد.
        val serviceIntent = Intent(context, AlarmService::class.java).apply {
            action = AlarmService.ACTION_START
            putExtra(EXTRA_NOTE_ID, noteId)
            putExtra(EXTRA_NOTE_TITLE, noteTitle)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not start alarm service", e)
        }
    }

    companion object {
        private const val TAG = "YadinoAlarm"

        const val EXTRA_NOTE_ID = "note_id"
        const val EXTRA_NOTE_TITLE = "note_title"

        fun pendingIntent(
            context: Context,
            noteId: String,
            title: String
        ): PendingIntent {
            val intent = Intent(context, AlarmReceiver::class.java).apply {
                putExtra(EXTRA_NOTE_ID, noteId)
                putExtra(EXTRA_NOTE_TITLE, title)
            }

            return PendingIntent.getBroadcast(
                context,
                noteId.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE
            )
        }

        fun cancel(
            context: Context,
            noteId: String,
            title: String = "یادآوری یادینو"
        ) {
            val alarmManager = context.getSystemService(
                Context.ALARM_SERVICE
            ) as AlarmManager

            alarmManager.cancel(pendingIntent(context, noteId, title))
        }
    }
}
