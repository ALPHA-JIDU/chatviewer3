package com.jihad.chatviewer.importer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.jihad.chatviewer.ChatViewerApp
import com.jihad.chatviewer.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

class ImportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        const val CHANNEL_ID = "import_progress"
        const val NOTIF_ID = 4101
        const val NOTIF_DONE_ID = 4102
    }

    override suspend fun doWork(): Result {
        val container = (applicationContext as ChatViewerApp).container
        val request = ImportRequestStore.load(applicationContext)
        if (request == null) {
            container.importState.value =
                ImportUiState(finished = true, failed = "Import request not found.")
            return Result.failure()
        }

        val total = container.importState.value.total
        container.importState.value = ImportUiState(running = true, text = "Starting...", total = total)
        try {
            setForeground(buildInfo("Starting...", 0L, total))
        } catch (e: Exception) {
            // keep going even if the system refuses the foreground notification
        }

        val pm = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "chatviewer:import")
        wakeLock.acquire(6 * 60 * 60 * 1000L)

        var lastDone = 0L
        var lastNotify = 0L

        fun report(text: String, done: Long) {
            lastDone = done
            container.importState.value =
                ImportUiState(running = true, text = text, done = done, total = total)
            val now = SystemClock.elapsedRealtime()
            if (now - lastNotify >= 1000L) {
                lastNotify = now
                updateNotification(text, done, total)
            }
        }

        try {
            val summary = withContext(Dispatchers.IO) {
                val walker = ExportWalker(
                    applicationContext,
                    { isActive },
                    { t -> report(t, lastDone) }
                )
                ExportImporter(
                    container.database,
                    walker,
                    request,
                    { isActive },
                    { t, d -> report(t, d) }
                ).run()
            }
            val resultText =
                "Chats imported: ${summary.chats}\n" +
                    "Messages read: ${summary.messagesRead}\n" +
                    "Already present (skipped): ${summary.duplicates}\n" +
                    "Unreadable files: ${summary.unreadable.size}"
            container.importState.value = ImportUiState(
                finished = true,
                resultText = resultText,
                unreadable = summary.unreadable
            )
            notifyDone("Import finished", "${summary.chats} chats, ${summary.messagesRead} messages")
            return Result.success()
        } catch (e: CancellationException) {
            container.importState.value = ImportUiState(finished = true, cancelled = true)
            throw e
        } catch (e: Exception) {
            container.importState.value =
                ImportUiState(finished = true, failed = e.message ?: "Unknown error")
            notifyDone("Import failed", e.message ?: "Unknown error")
            return Result.failure()
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
        }
    }

    // ---------- notifications ----------

    private fun ensureChannel() {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Import progress", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(applicationContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            applicationContext, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun buildNotification(text: String, done: Long, total: Long): Notification {
        ensureChannel()
        val cancel = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Importing chats")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancel)
        if (total > 0) {
            val p = ((done * 1000L) / total).toInt().coerceIn(0, 1000)
            builder.setProgress(1000, p, false)
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    private fun buildInfo(text: String, done: Long, total: Long): ForegroundInfo {
        val n = buildNotification(text, done, total)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIF_ID, n)
        }
    }

    private fun updateNotification(text: String, done: Long, total: Long) {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text, done, total))
    }

    private fun notifyDone(title: String, text: String) {
        ensureChannel()
        val n = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_DONE_ID, n)
    }
}
