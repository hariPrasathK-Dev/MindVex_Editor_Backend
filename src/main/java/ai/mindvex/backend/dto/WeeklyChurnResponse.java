package ai.mindvex.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record WeeklyChurnResponse(
        LocalDate weekStart,
        int linesAdded,
        int linesDeleted,
        int commitCount,
        BigDecimal churnRate) {
}
