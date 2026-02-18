package ai.mindvex.backend.dto;

public record BlameLineResponse(
        int lineNumber,
        String commitHash,
        String authorEmail,
        String committedAt, // ISO-8601 string
        String content) {
}
