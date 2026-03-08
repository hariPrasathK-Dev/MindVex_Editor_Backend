package ai.mindvex.backend.reasoning.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Enterprise Data Transfer Object for carrying complex multi-dimensional
 * AI reasoning results across system boundaries.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReasoningResultDto {
    private String repositoryUrl;
    private String analysisTimestamp;
    private int filesScanned;
    private long analysisDurationMs;
    
    private List<DesignPattern> detectedPatterns;
    private List<AntiPattern> antiPatterns;
    private List<RefactoringSuggestion> refactoringSuggestions;
    private List<MicroserviceBoundary> suggestedBoundaries;
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DesignPattern {
        private String name;
        private String category;
        private String confidenceScore;
        private String description;
        private List<String> implementingFiles;
        private String codeSnippet;
    }
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AntiPattern {
        private String severity; // CRITICAL, WARNING, INFO
        private String name;
        private String description;
        private String impact;
        private List<String> affectedFiles;
        private String remediationStrategy;
    }
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RefactoringSuggestion {
        private String targetFile;
        private String currentSmell;
        private String proposedArchitecture;
        private String effortLevel; // LOW, MEDIUM, HIGH, EPIC
        private List<String> stepByStepGuide;
    }
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MicroserviceBoundary {
        private String serviceName;
        private String businessDomain;
        private List<String> cohesiveModules;
        private List<String> externalDependencies;
        private String isolationComplexity;
    }
}
