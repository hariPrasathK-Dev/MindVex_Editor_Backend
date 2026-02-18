package ai.mindvex.backend.service;

import ai.mindvex.backend.dto.HoverResponse;
import ai.mindvex.backend.entity.ScipDocument;
import ai.mindvex.backend.entity.ScipOccurrence;
import ai.mindvex.backend.entity.ScipSymbolInfo;
import ai.mindvex.backend.repository.ScipDocumentRepository;
import ai.mindvex.backend.repository.ScipOccurrenceRepository;
import ai.mindvex.backend.repository.ScipSymbolInfoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Answers hover queries by joining scip_occurrences → scip_symbols.
 * Given a (userId, repoUrl, filePath, line, character), returns the
 * symbol metadata for the innermost occurrence at that position.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ScipQueryService {

    private final ScipDocumentRepository documentRepo;
    private final ScipOccurrenceRepository occurrenceRepo;
    private final ScipSymbolInfoRepository symbolInfoRepo;

    @Transactional(readOnly = true)
    public Optional<HoverResponse> getHover(
            Long userId, String repoUrl, String filePath, int line, int character) {

        Optional<ScipDocument> docOpt = documentRepo.findByUserIdAndRepoUrlAndRelativeUri(userId, repoUrl, filePath);

        if (docOpt.isEmpty()) {
            log.debug("No SCIP document found for user={} repo={} file={}", userId, repoUrl, filePath);
            return Optional.empty();
        }

        List<ScipOccurrence> occurrences = occurrenceRepo.findAtPosition(docOpt.get().getId(), line, character);

        if (occurrences.isEmpty()) {
            return Optional.empty();
        }

        // Take the innermost (smallest) occurrence
        ScipOccurrence occ = occurrences.get(0);

        Optional<ScipSymbolInfo> symbolInfo = symbolInfoRepo.findByUserIdAndRepoUrlAndSymbol(userId, repoUrl,
                occ.getSymbol());

        return Optional.of(HoverResponse.builder()
                .symbol(occ.getSymbol())
                .displayName(symbolInfo.map(ScipSymbolInfo::getDisplayName).orElse(null))
                .signatureDoc(symbolInfo.map(ScipSymbolInfo::getSignatureDoc).orElse(null))
                .documentation(symbolInfo.map(ScipSymbolInfo::getDocumentation).orElse(null))
                .startLine(occ.getStartLine())
                .startChar(occ.getStartChar())
                .endLine(occ.getEndLine())
                .endChar(occ.getEndChar())
                .build());
    }
}
