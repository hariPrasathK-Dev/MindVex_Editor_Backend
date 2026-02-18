package ai.mindvex.backend.repository;

import ai.mindvex.backend.entity.IndexJob;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.stereotype.Repository;

import jakarta.persistence.QueryHint;
import java.util.Optional;

@Repository
public interface IndexJobRepository extends JpaRepository<IndexJob, Long> {

    /**
     * Atomically claim the oldest pending job.
     * SKIP LOCKED ensures concurrent workers don't double-process the same job.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2")) // SKIP_LOCKED
    @Query("""
            SELECT j FROM IndexJob j
            WHERE j.status = 'pending'
            ORDER BY j.createdAt ASC
            LIMIT 1
            """)
    Optional<IndexJob> claimNextPendingJob();
}
