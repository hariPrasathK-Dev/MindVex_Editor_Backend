package ai.mindvex.backend.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailService {

    private final JavaMailSender mailSender;

    @Value("${app.mail.from}")
    private String fromEmail;

    @Value("${app.frontend.url:http://localhost:5173}")
    private String frontendUrl;

    /**
     * Send password reset email with token
     */
    public void sendPasswordResetEmail(String to, String token, String userName) {
        try {
            String resetUrl = frontendUrl + "/reset-password?token=" + token;

            String subject = "Reset Your CodeNexus Password";
            String htmlContent = buildPasswordResetEmailHtml(userName, resetUrl, token);

            sendHtmlEmail(to, subject, htmlContent);
            log.info("Password reset email sent to: {}", to);
        } catch (Exception e) {
            log.error("Failed to send password reset email to: {}", to, e);
            throw new RuntimeException("Failed to send password reset email", e);
        }
    }

    /**
     * Send HTML email
     */
    private void sendHtmlEmail(String to, String subject, String htmlContent) throws MessagingException {
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

        helper.setFrom(fromEmail);
        helper.setTo(to);
        helper.setSubject(subject);
        helper.setText(htmlContent, true);

        mailSender.send(message);
    }

    /**
     * Build HTML email content for password reset
     */
    private String buildPasswordResetEmailHtml(String userName, String resetUrl, String token) {
        return """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta charset="UTF-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <style>
                        body {
                            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif;
                            line-height: 1.6;
                            color: #333;
                            max-width: 600px;
                            margin: 0 auto;
                            padding: 20px;
                            background-color: #f5f5f5;
                        }
                        .container {
                            background-color: #ffffff;
                            border-radius: 8px;
                            padding: 40px;
                            box-shadow: 0 2px 4px rgba(0,0,0,0.1);
                        }
                        .header {
                            text-align: center;
                            margin-bottom: 30px;
                        }
                        .logo {
                            font-size: 28px;
                            font-weight: bold;
                            color: #f97316;
                            margin-bottom: 10px;
                        }
                        h1 {
                            color: #1a1a1a;
                            font-size: 24px;
                            margin-bottom: 20px;
                        }
                        p {
                            color: #666;
                            margin-bottom: 15px;
                        }
                        .button {
                            display: inline-block;
                            padding: 14px 32px;
                            background-color: #f97316;
                            color: #ffffff !important;
                            text-decoration: none;
                            border-radius: 6px;
                            font-weight: 600;
                            margin: 25px 0;
                            text-align: center;
                        }
                        .button:hover {
                            background-color: #ea580c;
                        }
                        .token-box {
                            background-color: #f9fafb;
                            border: 1px solid #e5e7eb;
                            border-radius: 6px;
                            padding: 15px;
                            margin: 20px 0;
                            word-break: break-all;
                            font-family: monospace;
                            font-size: 14px;
                            color: #1a1a1a;
                        }
                        .footer {
                            margin-top: 30px;
                            padding-top: 20px;
                            border-top: 1px solid #e5e7eb;
                            color: #9ca3af;
                            font-size: 12px;
                            text-align: center;
                        }
                        .warning {
                            background-color: #fef3c7;
                            border-left: 4px solid #f59e0b;
                            padding: 12px;
                            margin: 20px 0;
                            border-radius: 4px;
                            font-size: 14px;
                        }
                    </style>
                </head>
                <body>
                    <div class="container">
                        <div class="header">
                            <div class="logo">CodeNexus</div>
                        </div>

                        <h1>Reset Your Password</h1>

                        <p>Hi %s,</p>

                        <p>We received a request to reset your password for your CodeNexus account. Click the button below to create a new password:</p>

                        <div style="text-align: center;">
                            <a href="%s" class="button">Reset Password</a>
                        </div>

                        <p>Or copy and paste this link into your browser:</p>
                        <div class="token-box">%s</div>

                        <div class="warning">
                            <strong>⚠️ Security Notice:</strong><br>
                            This link will expire in 1 hour. If you didn't request a password reset, please ignore this email or contact support if you have concerns.
                        </div>

                        <p>If the button doesn't work, you can also enter this reset code manually:</p>
                        <div class="token-box">%s</div>

                        <div class="footer">
                            <p>This is an automated email from CodeNexus. Please do not reply to this email.</p>
                            <p>&copy; 2026 CodeNexus. All rights reserved.</p>
                        </div>
                    </div>
                </body>
                </html>
                """
                .formatted(userName, resetUrl, resetUrl, token);
    }
}
