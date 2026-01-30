package ai.mindvex.backend.service;

import ai.mindvex.backend.entity.OtpType;
import ai.mindvex.backend.entity.OtpVerification;
import ai.mindvex.backend.repository.OtpVerificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;

/**
 * Service for OTP generation and verification
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OtpService {

    private final OtpVerificationRepository otpRepository;
    private final EmailService emailService;

    private static final int OTP_LENGTH = 6;
    private static final int OTP_EXPIRY_MINUTES = 5;
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Generate and send OTP to email
     */
    @Transactional
    public void generateAndSendOtp(String email, OtpType type) {
        // Delete any existing OTPs for this email and type
        otpRepository.deleteByEmail(email);

        // Generate 6-digit OTP
        String otp = generateOtp();

        // Save OTP to database
        OtpVerification verification = OtpVerification.builder()
                .email(email)
                .otpCode(otp)
                .otpType(type)
                .expiresAt(LocalDateTime.now().plusMinutes(OTP_EXPIRY_MINUTES))
                .verified(false)
                .build();

        otpRepository.save(verification);
        log.info("OTP generated for email: {} type: {}", email, type);

        // Send email
        String purpose = type == OtpType.REGISTRATION ? "account registration" : "login";
        emailService.sendOtpEmail(email, otp, purpose);
    }

    /**
     * Verify OTP for email
     */
    @Transactional
    public boolean verifyOtp(String email, String otp, OtpType type) {
        return otpRepository.findByEmailAndOtpCodeAndOtpTypeAndVerifiedFalse(email, otp, type)
                .filter(v -> v.getExpiresAt().isAfter(LocalDateTime.now()))
                .map(v -> {
                    v.setVerified(true);
                    otpRepository.save(v);
                    log.info("OTP verified successfully for email: {}", email);
                    return true;
                })
                .orElseGet(() -> {
                    log.warn("OTP verification failed for email: {}", email);
                    return false;
                });
    }

    /**
     * Generate a random 6-digit OTP
     */
    private String generateOtp() {
        int otp = secureRandom.nextInt((int) Math.pow(10, OTP_LENGTH));
        return String.format("%0" + OTP_LENGTH + "d", otp);
    }

    /**
     * Clean up expired OTPs (can be called by a scheduled task)
     */
    @Transactional
    public void cleanupExpiredOtps() {
        otpRepository.deleteByExpiresAtBefore(LocalDateTime.now());
        log.info("Cleaned up expired OTPs");
    }
}
