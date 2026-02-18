package ai.mindvex.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record HotspotResponse(
        String filePath,
        BigDecimal avgChurnRate,
        int totalCommits,
        int totalLinesAdded,
        int totalLinesDeleted,
        List<WeekPoint> weeklyTrend) {
    public record WeekPoint(LocalDate weekStart, BigDecimal churnRate, int commits) {
    }
}
