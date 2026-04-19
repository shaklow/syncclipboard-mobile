package expo.modules.smsforwarder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import expo.modules.nativeutil.NativeLogger
import com.facebook.react.HeadlessJsTaskService

/**
 * 静态 BroadcastReceiver，在 AndroidManifest.xml 中注册。
 * 即使 app 进程被杀或处于 Doze 模式，系统仍会唤醒此 Receiver 处理短信。
 * （SMS_RECEIVED 属于隐式广播豁免列表，不受 Doze 限制）
 *
 * 仅当短信正文匹配验证码模式时才启动前台服务处理，避免无关短信触发通知。
 */
class StaticSmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "StaticSmsReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return

        val from = messages[0].displayOriginatingAddress ?: ""
        val body = messages.joinToString("") { it.messageBody ?: "" }

        NativeLogger.d(TAG, "Static receiver got SMS from=$from")

        // 仅含验证码的短信才启动前台服务
        if (!VerificationCodeExtractor.contains(body)) {
            NativeLogger.d(TAG, "SMS does not contain verification code, skipping")
            return
        }

        startHeadlessTask(context, from, body)
    }

    /**
     * 启动 Headless JS 任务 Service，在后台执行验证码提取与上传。
     * 使用 startForegroundService 以满足 Android 8+ 后台 Service 启动限制。
     */
    private fun startHeadlessTask(context: Context, from: String, body: String) {
        try {
            val serviceIntent = Intent(context, SmsHeadlessTaskService::class.java).apply {
                putExtra("from", from)
                putExtra("body", body)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            HeadlessJsTaskService.acquireWakeLockNow(context)
            NativeLogger.d(TAG, "Headless task service started for SMS from=$from")
        } catch (e: Exception) {
            NativeLogger.e(TAG, "Failed to start headless task service", e)
        }
    }
}
