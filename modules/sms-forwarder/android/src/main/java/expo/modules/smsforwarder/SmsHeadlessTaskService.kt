package expo.modules.smsforwarder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.facebook.react.HeadlessJsTaskService
import com.facebook.react.bridge.Arguments
import com.facebook.react.jstasks.HeadlessJsTaskConfig

/**
 * Headless JS 任务服务 — 在后台（无 Activity / 无 UI）启动 React Native JS 运行时，
 * 执行短信验证码提取与上传。
 *
 * 由 [StaticSmsReceiver] 通过 `startForegroundService()` 启动。
 * 启动后立即调用 `startForeground()` 以满足 Android 8+ 要求。
 *
 * 上传成功后启动 60 秒倒计时，通知显示「已上传验证码：xxx / xx秒后关闭」，
 * 倒计时结束后自动移除前台服务。新 SMS 到来会打断倒计时并重新处理。
 */
class SmsHeadlessTaskService : HeadlessJsTaskService() {

    companion object {
        private const val TAG = "SmsHeadlessTask"
        const val CHANNEL_ID = "syncclipboard_sms_headless"
        const val CHANNEL_NAME = "短信验证码上传"
        const val NOTIFICATION_ID = 0x2022
        const val TASK_NAME = "SmsUploadTask"
        const val ACTION_DISMISS = "expo.modules.smsforwarder.ACTION_DISMISS_COUNTDOWN"
        /** 任务超时：60 秒（含重试时间） */
        private const val TASK_TIMEOUT_MS = 60_000L
        /** 上传成功后倒计时秒数 */
        private const val COUNTDOWN_SECONDS = 60

        @Volatile
        private var instance: SmsHeadlessTaskService? = null

        /**
         * 从任意位置更新 Headless 服务的前台通知文本。
         * JS 侧通过 SmsForwarderModule.updateSmsUploadNotification() 调用。
         */
        fun updateNotificationText(context: Context, text: String) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return
            ensureNotificationChannel(context, nm)
            nm.notify(NOTIFICATION_ID, buildStaticNotification(
                context, "SyncClipboard", text,
                dismissButtonLabel = "取消"
            ))
        }

        /**
         * 由 JS 侧调用：上传成功后启动倒计时。
         * 倒计时期间通知显示「已上传验证码：code / xx秒后关闭」，结束后自动停止服务。
         */
        fun startCountdown(code: String) {
            instance?.beginCountdown(code)
        }

        private fun ensureNotificationChannel(context: Context, nm: NotificationManager) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "短信验证码后台上传通知"
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
                nm.createNotificationChannel(channel)
            }
        }

        private fun getNotificationIcon(context: Context): Int {
            return context.resources.getIdentifier(
                "ic_notification", "drawable", context.packageName
            ).takeIf { it != 0 }
                ?: context.resources.getIdentifier(
                    "ic_launcher_foreground", "mipmap", context.packageName
                ).takeIf { it != 0 }
                ?: android.R.drawable.ic_menu_info_details
        }

        private fun buildStaticNotification(
            context: Context,
            title: String,
            text: String,
            dismissButtonLabel: String? = null
        ): Notification {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            ensureNotificationChannel(context, nm)
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(getNotificationIcon(context))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(true)
            if (dismissButtonLabel != null) {
                val dismissIntent = Intent(context, SmsHeadlessTaskService::class.java).apply {
                    action = ACTION_DISMISS
                }
                val pendingIntent = PendingIntent.getForegroundService(
                    context, 0, dismissIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                builder.addAction(0, dismissButtonLabel, pendingIntent)
            }
            return builder.build()
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var countdownRunnable: Runnable? = null
    private var isCountdownActive = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        startForeground(NOTIFICATION_ID, buildStaticNotification(this, "SyncClipboard", "正在处理短信验证码…"))
        Log.d(TAG, "SmsHeadlessTaskService created, foreground notification posted")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // “立刻关闭”按钮触发
        if (intent?.action == ACTION_DISMISS) {
            Log.d(TAG, "Dismiss action received, stopping service")
            cancelCountdown()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        // 新 SMS 到来 → 取消现有倒计时，重新处理
        if (isCountdownActive) {
            cancelCountdown()
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.notify(NOTIFICATION_ID, buildStaticNotification(this, "SyncClipboard", "正在处理短信验证码…"))
            Log.d(TAG, "Countdown cancelled due to new SMS")
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun getTaskConfig(intent: Intent?): HeadlessJsTaskConfig? {
        val extras: Bundle = intent?.extras ?: return null
        val from = extras.getString("from", "")
        val body = extras.getString("body", "")
        if (body.isNullOrEmpty()) return null

        Log.d(TAG, "Creating headless task config for SMS from=$from")

        return HeadlessJsTaskConfig(
            TASK_NAME,
            Arguments.fromBundle(extras),
            TASK_TIMEOUT_MS,
            true  // allowedInForeground: 即使 app 在前台也可以执行
        )
    }

    override fun onHeadlessJsTaskFinish(taskId: Int) {
        if (isCountdownActive) {
            // 倒计时进行中，不停止服务，由倒计时结束后负责停止
            Log.d(TAG, "Headless task finished, countdown active, keeping service alive")
        } else {
            Log.d(TAG, "Headless task finished, no countdown, stopping service")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        cancelCountdown()
        instance = null
        super.onDestroy()
    }

    // ---- 倒计时逻辑 ----

    private fun beginCountdown(code: String) {
        cancelCountdown()
        isCountdownActive = true
        var remaining = COUNTDOWN_SECONDS
        Log.d(TAG, "Starting ${COUNTDOWN_SECONDS}s countdown for code=$code")

        countdownRunnable = object : Runnable {
            override fun run() {
                if (remaining > 0) {
                    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    nm?.notify(
                        NOTIFICATION_ID,
                        buildStaticNotification(
                            this@SmsHeadlessTaskService,
                            "已上传验证码：$code",
                            "${remaining}秒后关闭",
                            dismissButtonLabel = "立刻关闭"
                        )
                    )
                    remaining--
                    handler.postDelayed(this, 1000)
                } else {
                    Log.d(TAG, "Countdown finished, stopping service")
                    isCountdownActive = false
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        handler.post(countdownRunnable!!)
    }

    private fun cancelCountdown() {
        countdownRunnable?.let { handler.removeCallbacks(it) }
        countdownRunnable = null
        isCountdownActive = false
    }
}
