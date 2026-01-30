package ai.mindvex.backend.repository;

import ai.mindvex.backend.entity.OtpType;
import ai.mindvex.backend.entity.OtpVerification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface OtpVerificationRepository extends JpaRepository<OtpVerification, Long> {

    /**
     * Find a valid (non-verified) OTP for an email
     */
    Optional<OtpVerification> findByEmailAndOtpCodeAndOtpTypeAndVerifiedFalse(
            String email, String otpCode, OtpType otpType);

    /**
     * Find the latest OTP for an email and type
     */
    Optional<OtpVerification> findFirstByEmailAndOtpTypeOrderByCreatedAtDesc(
            String email, OtpType otpType);

    /**
     * Delete all OTPs for an email
     */
    @Modifying
    void deleteByEmail(String email);

    /**
     * Delete expired OTPs
     */
    @Modifying
    void deleteByExpiresAtBefore(LocalDateTime time);
}
