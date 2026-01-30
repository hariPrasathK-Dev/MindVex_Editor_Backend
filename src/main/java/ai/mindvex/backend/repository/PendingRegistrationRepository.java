package ai.mindvex.backend.repository;

import ai.mindvex.backend.entity.PendingRegistration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface PendingRegistrationRepository extends JpaRepository<PendingRegistration, Long> {

    /**
     * Find pending registration by email
     */
    Optional<PendingRegistration> findByEmail(String email);

    /**
     * Check if a pending registration exists for email
     */
    boolean existsByEmail(String email);

    /**
     * Delete pending registration by email
     */
    @Modifying
    void deleteByEmail(String email);

    /**
     * Delete expired pending registrations
     */
    @Modifying
    void deleteByExpiresAtBefore(LocalDateTime time);
}
