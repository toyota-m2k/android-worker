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

    var prevPercent: Int = -1
    var prevTick: Long = 0L

    private fun prepare(
        title: String,
        text: String,
        icon: Int,
        progressInPercent: Int = -1,
        onGoing:Boolean=true) : NotificationCompat.Builder {
        return NotificationCompat.Builder(applicationContext, channelId)
            .apply {
                if (title.isNotEmpty()) {
                    setContentTitle(title)
                }
                if( text.isNotEmpty()) {
                    setContentText(text)
                }
                if (progressInPercent>=0) {
                    setProgress(100, progressInPercent, false) // プログレスバーを表示
                }
            }
            .setSmallIcon(icon)
            .setOngoing(onGoing)
    }

    private fun NotificationCompat.Builder.notify() {
        val notificationManager = applicationContext.getSystemService(NotificationManager::class.java)
        notificationManager.notify(notificationId, this.build())
    }

    fun initialNotification(
        title: String,
        text: String,
        icon: Int): Notification {
        return prepare(title, text, icon).build()
    }

    fun message(
        title: String,
        text: String,
        icon: Int,
        onGoing:Boolean=true) {
        prepare(title, text, icon, onGoing=onGoing).notify()
    }
    fun message(
        title: String,
        text: String,
        uploading:Boolean,
        onGoing:Boolean=true) {
        message(title, text, if(uploading) DEFAULT_UPLOAD_ICON else DEFAULT_DOWNLOAD_ICON, onGoing)
    }
    fun progress(
        progressInPercent: Int,
        title: String,
        text: String,
        icon: Int,
        onGoing:Boolean=true
    ) {
        if (onGoing && (progressInPercent == prevPercent ||  System.currentTimeMillis() - prevTick  < 1000)) {
            // 進捗率が変化していない、または、１秒以内の再通知 --> 抑制
            return
        }
        prevPercent = progressInPercent
        prevTick = System.currentTimeMillis()
        prepare(title, text, icon, progressInPercent, onGoing).notify()
    }

    fun progress(
        progressInPercent: Int,
        title: String,
        text: String,
        uploading:Boolean,
        onGoing:Boolean=true
    ) {
        progress(progressInPercent, title, text, if(uploading) DEFAULT_UPLOAD_ICON else DEFAULT_DOWNLOAD_ICON, onGoing)
    }
}