package ly.openwave.identity.service

import jakarta.mail.internet.InternetAddress
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.MimeMessageHelper
import org.springframework.stereotype.Service

interface PortalCredentialNotificationService {
    fun sendCredentialEmail(to: String?, displayName: String, username: String, temporaryPassword: String): Boolean
    fun sendPasswordResetLink(to: String?, displayName: String, resetLink: String): Boolean
}

@Service
@ConditionalOnProperty(name = ["identity.notification.email.enabled"], havingValue = "true")
class SmtpPortalCredentialNotificationService(
    private val mailSender: JavaMailSender,
    @Value("\${identity.notification.email.from}") private val fromAddress: String,
    @Value("\${identity.notification.email.from-name}") private val fromName: String,
    @Value("\${identity.notification.email.portal-url}") private val portalUrl: String
) : PortalCredentialNotificationService {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun sendCredentialEmail(to: String?, displayName: String, username: String, temporaryPassword: String): Boolean {
        if (to.isNullOrBlank()) return false
        return try {
            val message = mailSender.createMimeMessage()
            val helper = MimeMessageHelper(message, true, Charsets.UTF_8.name())
            helper.setFrom(InternetAddress(fromAddress, fromName))
            helper.setTo(to)
            helper.setSubject("OpenWave Identity portal credentials")
            helper.setText(plainBody(displayName, username, temporaryPassword), htmlBody(displayName, username, temporaryPassword))
            mailSender.send(message)
            true
        } catch (ex: Exception) {
            log.warn("Failed to send OpenWave Identity credential email to {}: {}", to, ex.message)
            false
        }
    }

    override fun sendPasswordResetLink(to: String?, displayName: String, resetLink: String): Boolean {
        if (to.isNullOrBlank()) return false
        return try {
            val message = mailSender.createMimeMessage()
            val helper = MimeMessageHelper(message, true, Charsets.UTF_8.name())
            helper.setFrom(InternetAddress(fromAddress, fromName))
            helper.setTo(to)
            helper.setSubject("Reset your OpenWave Identity password")
            helper.setText(
                """
                Dear $displayName,

                Use this secure link to reset your OpenWave Identity portal password:

                $resetLink

                The link is valid for 10 minutes and can be used only once. If you did not request it, ignore this email.
                """.trimIndent(),
                resetLinkHtmlBody(displayName, resetLink)
            )
            mailSender.send(message)
            true
        } catch (ex: Exception) {
            log.warn("Failed to send OpenWave Identity password reset OTP to {}: {}", to, ex.message)
            false
        }
    }

    private fun plainBody(displayName: String, username: String, temporaryPassword: String) = """
        Dear $displayName,

        Your OpenWave Identity portal credentials are ready.

        Portal URL: $portalUrl
        Username: $username
        Temporary password: $temporaryPassword

        Please sign in and change this temporary password immediately. Keep these credentials private.

        OpenWave Identity
    """.trimIndent()

    private fun htmlBody(displayName: String, username: String, temporaryPassword: String) = """
        <!doctype html>
        <html lang="en">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1.0">
          <title>OpenWave Identity access</title>
        </head>
        <body style="margin:0;padding:0;background:#f4f6f8;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Arial,sans-serif;color:#12213a;">
          <div style="max-width:640px;margin:0 auto;padding:28px 14px;">
            <div style="background:#ffffff;border:1px solid #e2e8f0;border-radius:20px;overflow:hidden;box-shadow:0 16px 42px rgba(15,23,42,.08);">
              <div style="padding:28px 30px 20px;border-bottom:1px solid #e8edf3;">
                <table style="width:100%;border-collapse:collapse;">
                  <tr>
                    <td style="font-size:28px;line-height:1;font-weight:800;letter-spacing:.01em;color:#173865;">Neptune<span style="color:#ef5350;">.</span></td>
                    <td style="text-align:right;font-size:12px;line-height:1.4;color:#64748b;text-transform:uppercase;letter-spacing:.08em;font-weight:700;">OpenWave<br>Identity</td>
                  </tr>
                </table>
                <h1 style="margin:26px 0 8px;font-size:24px;line-height:1.25;color:#0f172a;">Identity portal access</h1>
                <p style="margin:0;font-size:15px;line-height:1.65;color:#526174;">Dear ${escape(displayName)}, your OpenWave Identity portal credentials are ready.</p>
              </div>
              <div style="padding:26px 30px 30px;">
                <div style="border:1px solid #e4eaf1;border-radius:16px;padding:16px 18px;margin:0 0 16px;background:#fbfcfe;">
                  <p style="margin:0 0 10px;font-size:12px;color:#173865;font-weight:800;letter-spacing:.07em;text-transform:uppercase;">Portal access</p>
                  <table style="width:100%;border-collapse:collapse;">
                    <tr>
                      <td style="padding:10px 0;color:#64748b;width:170px;border-top:0;">Portal URL</td>
                      <td style="padding:10px 0;text-align:right;font-weight:700;word-break:break-word;border-top:0;"><a href="${escape(portalUrl)}" style="color:#173865;text-decoration:none;">${escape(portalUrl)}</a></td>
                    </tr>
                    <tr>
                      <td style="padding:10px 0;color:#64748b;border-top:1px solid #edf2f7;">Username</td>
                      <td style="padding:10px 0;text-align:right;font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;font-weight:700;border-top:1px solid #edf2f7;">${escape(username)}</td>
                    </tr>
                    <tr>
                      <td style="padding:10px 0;color:#64748b;border-top:1px solid #edf2f7;">Temporary password</td>
                      <td style="padding:10px 0;text-align:right;font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;font-weight:700;word-break:break-word;border-top:1px solid #edf2f7;">${escape(temporaryPassword)}</td>
                    </tr>
                  </table>
                </div>
                <div style="margin:16px 0 0;padding:14px 16px;border-radius:14px;background:#fff8eb;border:1px solid #f4d79b;color:#7a4b00;font-size:13px;line-height:1.55;">
                  Please sign in and change this temporary password immediately. Keep these credentials private and do not forward this email.
                </div>
                <a href="${escape(portalUrl)}" style="display:inline-block;margin-top:18px;background:#173865;color:#ffffff;text-decoration:none;font-weight:700;padding:13px 22px;border-radius:12px;">Open Identity portal</a>
              </div>
              <div style="padding:18px 30px 24px;border-top:1px solid #e8edf3;color:#7b8794;font-size:12px;line-height:1.55;">
                This message was sent by OpenWave Identity. It is separate from Neptune Astro gateway access.
              </div>
            </div>
          </div>
        </body>
        </html>
    """.trimIndent()

    private fun resetLinkHtmlBody(displayName: String, resetLink: String) = """
        <!doctype html>
        <html lang="en">
        <head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1.0"><title>Password reset</title></head>
        <body style="margin:0;padding:0;background:#f4f6f8;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Arial,sans-serif;color:#12213a;">
          <div style="max-width:560px;margin:0 auto;padding:28px 14px;">
            <div style="background:#fff;border:1px solid #e2e8f0;border-radius:20px;overflow:hidden;box-shadow:0 16px 42px rgba(15,23,42,.08);">
              <div style="padding:28px 30px 20px;border-bottom:1px solid #e8edf3;">
                <table style="width:100%;border-collapse:collapse;"><tr><td style="font-size:28px;font-weight:800;color:#173865;">Neptune<span style="color:#ef5350;">.</span></td><td style="text-align:right;font-size:12px;line-height:1.4;color:#64748b;text-transform:uppercase;letter-spacing:.08em;font-weight:700;">OpenWave<br>Identity</td></tr></table>
                <h1 style="margin:24px 0 8px;font-size:23px;color:#0f172a;">Reset your password</h1>
              </div>
              <div style="padding:30px;text-align:center;">
                <p style="font-size:13px;line-height:1.55;color:#64748b;">Dear ${escape(displayName)}, use this secure link to reset your OpenWave Identity portal password.</p>
                <a href="${escape(resetLink)}" style="display:inline-block;margin:18px 0 12px;background:#173865;color:#ffffff;text-decoration:none;font-weight:800;padding:14px 22px;border-radius:12px;">Reset password</a>
                <div style="word-break:break-all;margin-top:12px;padding:14px;border-radius:14px;background:#f8fafc;border:1px solid #e2e8f0;color:#173865;font-size:12px;line-height:1.5;text-align:left;">${escape(resetLink)}</div>
                <p style="font-size:13px;line-height:1.55;color:#64748b;">Valid for 10 minutes and usable only once. Ignore this email if you did not request it.</p>
              </div>
              <div style="padding:18px 30px 24px;border-top:1px solid #e8edf3;color:#7b8794;font-size:12px;text-align:center;">This message was sent by OpenWave Identity. Do not reply.</div>
            </div>
          </div>
        </body>
        </html>
    """.trimIndent()

    private fun escape(value: String): String =
        value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
}

@Service
@ConditionalOnProperty(name = ["identity.notification.email.enabled"], havingValue = "false", matchIfMissing = true)
class NoopPortalCredentialNotificationService : PortalCredentialNotificationService {
    override fun sendCredentialEmail(to: String?, displayName: String, username: String, temporaryPassword: String): Boolean = false
    override fun sendPasswordResetLink(to: String?, displayName: String, resetLink: String): Boolean = false
}
