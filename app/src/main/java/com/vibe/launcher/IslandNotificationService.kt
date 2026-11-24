package com.vibe.launcher

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.content.Intent

class IslandNotificationService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val pkg = sbn.packageName ?: return
        val extras = sbn.notification?.extras
        val title = extras?.getCharSequence("android.title")?.toString() ?: ""
        val text = extras?.getCharSequence("android.text")?.toString() ?: ""
        val category = sbn.notification?.category
        val isMedia = category == android.app.Notification.CATEGORY_TRANSPORT || pkg.contains("music") || pkg.contains("spotify") || pkg.contains("youtube") || pkg.contains("soundcloud")
        val isCall = category == android.app.Notification.CATEGORY_CALL
        val isRecord = pkg.contains("screenrecord") || title.contains("запис", true) || title.contains("record", true)
        val state = when {
            isCall -> "call"
            isRecord -> "record"
            isMedia -> "music"
            else -> return
        }
        val i = Intent("com.vibe.launcher.ISLAND_EVENT")
        i.setPackage(packageName)
        i.putExtra("state", state)
        i.putExtra("title", title)
        i.putExtra("text", text)
        i.putExtra("pkg", pkg)
        sendBroadcast(i)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        val category = sbn.notification?.category
        if (category == android.app.Notification.CATEGORY_TRANSPORT || category == android.app.Notification.CATEGORY_CALL) {
            val i = Intent("com.vibe.launcher.ISLAND_EVENT")
            i.setPackage(packageName)
            i.putExtra("state", "idle")
            sendBroadcast(i)
        }
    }
}
