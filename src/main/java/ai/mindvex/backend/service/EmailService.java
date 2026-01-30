package ai.mindvex.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Service for sending emails
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmailService {

    private final JavaMailSender mailSender;

    @Value("${spring.mail.username:noreply@mindvex.ai}")
    private String fromEmail;

    /**
     * Send OTP verification email
     */
    @Async
    public void sendOtpEmail(String to, String otp, String purpose) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromEmail);
            message.setTo(to);
            message.setSubject("MindVex - Your Verification Code");
            message.setText(buildOtpEmailBody(otp, purpose));

            mailSender.send(message);
            log.info("OTP email sent successfully to: {}", to);
        } catch (Exception e) {
            log.error("Failed to send OTP email to: {}", to, e);
            // In production, you might want to throw an exception here
            // For development, we'll log the OTP so testing is possible without email
            log.warn("Email sending failed. OTP for {} is: {}", to, otp);
        }
    }

    private String buildOtpEmailBody(String otp, String purpose) {
        return String.format("""
                Hello,

                Your verification code for %s is:

                %s

                This code will expire in 5 minutes.

                If you didn't request this code, please ignore this email.

                Best regards,
                The MindVex Team
                """, purpose, otp);
    }
}
