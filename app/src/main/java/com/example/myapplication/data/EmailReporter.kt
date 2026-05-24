package com.example.myapplication.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Properties
import javax.mail.Authenticator
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

object EmailReporter {
    private const val TAG = "EmailReporter"
    private const val PREFS_NAME = "email_config"
    private const val KEY_SENDER = "sender"
    private const val KEY_AUTH_CODE = "auth_code"
    private const val KEY_RECIPIENT = "recipient"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_SEND_HOUR = "send_hour"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun getSender(): String = prefs.getString(KEY_SENDER, "") ?: ""

    fun setSender(email: String) {
        prefs.edit().putString(KEY_SENDER, email).apply()
    }

    fun getAuthCode(): String = prefs.getString(KEY_AUTH_CODE, "") ?: ""

    fun setAuthCode(code: String) {
        prefs.edit().putString(KEY_AUTH_CODE, code).apply()
    }

    fun getRecipient(): String = prefs.getString(KEY_RECIPIENT, "") ?: ""

    fun setRecipient(email: String) {
        prefs.edit().putString(KEY_RECIPIENT, email).apply()
    }

    fun isConfigured(): Boolean {
        return getSender().isNotBlank() && getAuthCode().isNotBlank() && getRecipient().isNotBlank()
    }

    fun getSendHour(): Int = prefs.getInt(KEY_SEND_HOUR, 22)

    fun setSendHour(hour: Int) {
        prefs.edit().putInt(KEY_SEND_HOUR, hour).apply()
    }

    fun sendAlert(alertType: String, detail: String) {
        if (!isEnabled() || !isConfigured()) return
        Thread {
            try {
                val sender = getSender()
                val authCode = getAuthCode()
                val recipient = getRecipient()

                val props = Properties().apply {
                    put("mail.smtp.host", "smtp.qq.com")
                    put("mail.smtp.port", "587")
                    put("mail.smtp.auth", "true")
                    put("mail.smtp.starttls.enable", "true")
                    put("mail.smtp.connectiontimeout", "10000")
                    put("mail.smtp.timeout", "10000")
                    put("mail.smtp.writetimeout", "10000")
                }

                val session = Session.getInstance(props, object : Authenticator() {
                    override fun getPasswordAuthentication(): PasswordAuthentication {
                        return PasswordAuthentication(sender, authCode)
                    }
                })

                val nowStr = java.time.LocalDateTime.now(java.time.ZoneId.of("+08:00"))
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                val message = MimeMessage(session).apply {
                    setFrom(InternetAddress(sender))
                    setRecipient(Message.RecipientType.TO, InternetAddress(recipient))
                    subject = "⚠️ GameTime 安全告警 | $alertType"
                    setText("GameTime 安全告警\n\n类型: $alertType\n时间: $nowStr\n详情: $detail\n\n请立即检查设备。", "UTF-8")
                }

                Transport.send(message)
                Log.d(TAG, "Alert email sent: $alertType")
            } catch (e: Exception) {
                Log.e(TAG, "Alert email failed: ${e.message}")
            }
        }.start()
    }

    suspend fun sendReport(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val sender = getSender()
            val authCode = getAuthCode()
            val recipient = getRecipient()

            if (!isConfigured()) {
                return@withContext Result.failure(Exception("邮件未配置"))
            }

            val htmlReport = ActivityLog.getTodayHtmlReport()

            val props = Properties().apply {
                put("mail.smtp.host", "smtp.qq.com")
                put("mail.smtp.port", "587")
                put("mail.smtp.auth", "true")
                put("mail.smtp.starttls.enable", "true")
                put("mail.smtp.connectiontimeout", "10000")
                put("mail.smtp.timeout", "10000")
                put("mail.smtp.writetimeout", "10000")
            }

            val session = Session.getInstance(props, object : Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication {
                    return PasswordAuthentication(sender, authCode)
                }
            })

            val todayStr = java.time.LocalDate.now(java.time.ZoneId.of("+08:00")).toString()
            val message = MimeMessage(session).apply {
                setFrom(InternetAddress(sender))
                setRecipient(Message.RecipientType.TO, InternetAddress(recipient))
                subject = "GameTime 每日报告 | $todayStr"
                setContent(htmlReport, "text/html; charset=UTF-8")
            }

            Transport.send(message)
            Log.d(TAG, "Email sent successfully to $recipient")
            Result.success("邮件发送成功: $todayStr")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send email: ${e.message}", e)
            Result.failure(e)
        }
    }
}
