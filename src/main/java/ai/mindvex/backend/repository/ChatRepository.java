package ai.mindvex.backend.repository;

import ai.mindvex.backend.entity.Chat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ChatRepository extends JpaRepository<Chat, Long> {
    
    List<Chat> findByWorkspaceId(Long workspaceId);
    
    Optional<Chat> findByIdAndWorkspaceId(Long id, Long workspaceId);
}
