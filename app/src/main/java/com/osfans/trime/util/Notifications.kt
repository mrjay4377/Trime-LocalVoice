/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Color
import android.os.Build
import androidx.core.app.NotificationCompat
import com.osfans.trime.R
import com.osfans.trime.ui.main.MainActivity
import com.osfans.trime.ui.main.NavigationRoute
import splitties.systemservices.notificationManager

fun createNotificationChannel(
    id: String,
    name: String,
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val channel =
            NotificationChannel(
                id,
                name,
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = id }
        notificationManager.createNotificationChannel(channel)
    }
}

object DeployNotification {
    const val CHANNEL_ID = "rime-daemon"
    const val MESSAGE_ID = 2331
    private const val EXTERNAL_SYNC_FALLBACK_MESSAGE_ID = 2333

    /** 部署各阶段的说明，由 Kotlin 层设置；native 的 deploy/start 消息会复用它。 */
    @Volatile
    private var stageText: String? = null

    fun ensureChannel() {
        createNotificationChannel(CHANNEL_ID, appContext.getString(R.string.rime_daemon))
    }

    /** 更新「正在部署」通知的文字，让用户知道当前卡在同步还是首次编译词库。 */
    fun showStage(text: String) {
        stageText = text
        showProgress()
    }

    fun clearStage() {
        stageText = null
    }

    fun showProgress() {
        ensureChannel()
        NotificationCompat
            .Builder(appContext, CHANNEL_ID)
            .setContentTitle(appContext.getString(R.string.rime_daemon))
            .setSmallIcon(R.drawable.ic_baseline_refresh_reversed_24)
            .setContentText(stageText ?: appContext.getString(R.string.deploy_progress))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(stageText ?: appContext.getString(R.string.deploy_progress)),
            )
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
            .let { notificationManager.notify(MESSAGE_ID, it) }
    }

    fun showSuccess() {
        clearStage()
        ensureChannel()
        NotificationCompat
            .Builder(appContext, CHANNEL_ID)
            .setContentTitle(appContext.getString(R.string.rime_daemon))
            .setSmallIcon(R.drawable.ic_baseline_refresh_reversed_24)
            .setColor(Color.GREEN)
            .setContentText(appContext.getString(R.string.deploy_finish))
            .setOngoing(false)
            .setTimeoutAfter(3000L)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
            .let { notificationManager.notify(MESSAGE_ID, it) }
    }

    fun showFailure(contentIntent: PendingIntent) {
        clearStage()
        ensureChannel()
        NotificationCompat
            .Builder(appContext, CHANNEL_ID)
            .setContentTitle(appContext.getString(R.string.rime_daemon))
            .setSmallIcon(R.drawable.ic_baseline_warning_24)
            .setColor(Color.YELLOW)
            .setContentText(appContext.getString(R.string.view_deploy_failure_log))
            .setContentIntent(contentIntent)
            .setOngoing(false)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
            .let { notificationManager.notify(MESSAGE_ID, it) }
    }

    fun cancel() {
        clearStage()
        notificationManager.cancel(MESSAGE_ID)
    }

    fun promptDataPathPermissionRevoked() {
        val message = appContext.getString(R.string.data_path_permission_revoked)
        val contentIntent =
            PendingIntent.getActivity(
                appContext,
                EXTERNAL_SYNC_FALLBACK_MESSAGE_ID,
                Intent(appContext, MainActivity::class.java).apply {
                    action = Intent.ACTION_RUN
                    putExtra(MainActivity.EXTRA_SETTINGS_ROUTE, NavigationRoute.Profile)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        ensureChannel()
        NotificationCompat
            .Builder(appContext, CHANNEL_ID)
            .setContentTitle(appContext.getString(R.string.rime_daemon))
            .setSmallIcon(R.drawable.ic_baseline_warning_24)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
            .let { notificationManager.notify(EXTERNAL_SYNC_FALLBACK_MESSAGE_ID, it) }
    }
}
