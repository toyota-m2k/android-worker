package io.github.toyota32k.utils.worker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat

class NotificationProcessor(
    val applicationContext: Context,
    val channelId: String = DEFAULT_CHANNEL_ID,
    channelName: String = DEFAULT_CHANNEL_NAME,
    importance: Int = DEFAULT_IMPORTANCE, // NotificationManager.IMPORTANCE_LOW
    val notificationId: Int = 1,
) {
    companion object {
        const val DEFAULT_CHANNEL_ID = "worker_notification_channel2"
        const val DEFAULT_CHANNEL_NAME = "Worker Channel2"
        val DEFAULT_IMPORTANCE = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) NotificationManager.IMPORTANCE_LOW else 2
        const val DEFAULT_UPLOAD_ICON:Int = android.R.drawable.stat_sys_upload
        const val DEFAULT_DOWNLOAD_ICON:Int = android.R.drawable.stat_sys_download
    }

    init {
        require(channelId.isNotEmpty()) { "Channel ID must not be empty" }
        require(channelName.isNotEmpty()) { "Channel Name must not be empty" }
        require(notificationId > 0) { "Notification ID must be non-negative" }
        // Android 8.0 (API 26)以降は通知チャンネルが必要
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                channelName,
                importance.coerceIn(NotificationManager.IMPORTANCE_LOW, NotificationManager.IMPORTANCE_HIGH)
            )
            val notificationManager = applicationContext.getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun message(
        title: String,
        text: String,
        icon: Int,
        onGoing:Boolean=true): NotificationCompat.Builder {
        return NotificationCompat.Builder(applicationContext, channelId)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(icon)
            .setOngoing(onGoing) // ユーザーがスワイプで消せないようにする
    }
    fun message(
        title: String,
        text: String,
        uploading:Boolean,
        onGoing:Boolean=true): NotificationCompat.Builder {
        return message(title, text, if(uploading) DEFAULT_UPLOAD_ICON else DEFAULT_DOWNLOAD_ICON, onGoing)
    }
    fun progress(
        title: String,
        text: String,
        icon: Int,
        progressInPercent: Int,
        onGoing:Boolean=true
    ): NotificationCompat.Builder {
        return NotificationCompat.Builder(applicationContext, channelId)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(icon)
            .setProgress(100, progressInPercent, false) // プログレスバーを表示
            .setOngoing(onGoing) // 完了するまではユーザーがスワイプで消せないようにする
    }
    fun progress(
        title: String,
        text: String,
        uploading:Boolean,
        progressInPercent: Int,
        onGoing:Boolean=true
    ): NotificationCompat.Builder {
        return progress(title, text, if(uploading) DEFAULT_UPLOAD_ICON else DEFAULT_DOWNLOAD_ICON, progressInPercent, onGoing)
    }

    fun notify(notification: Notification) {
        val notificationManager = applicationContext.getSystemService(NotificationManager::class.java)
        notificationManager.notify(notificationId, notification)
    }
}