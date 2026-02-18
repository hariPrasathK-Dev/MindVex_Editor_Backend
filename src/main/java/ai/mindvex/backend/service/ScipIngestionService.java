package ai.mindvex.backend.service;

import ai.mindvex.backend.entity.*;
import ai.mindvex.backend.repository.*;
import com.google.protobuf.CodedInputStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses a raw SCIP Protobuf binary and ingests it into the three
 * code_intelligence tables: scip_documents, scip_occurrences, scip_symbols.
 *
 * SCIP wire format (proto3):
 * message Index {
 * repeated Document documents = 3;
 * repeated SymbolInformation external_symbols = 4;
 * }
 * message Document {
 * string relative_path = 1;
 * string language = 4;
 * repeated Occurrence occurrences = 5;
 * repeated SymbolInformation symbols = 6;
 * }
 * message Occurrence {
 * string symbol = 1;
 * repeated int32 range = 3; // [startLine, startChar, endLine, endChar]
 * int32 symbol_roles = 4;
 * }
 * message SymbolInformation {
 * string symbol = 1;
 * repeated string documentation = 3;
 * SignatureDocumentation signature_documentation = 8;
 * }
 *
 * We parse the binary manually using CodedInputStream to avoid needing
 * generated proto classes — keeping the build simple.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ScipIngestionService {

    private final ScipDocumentRepository documentRepo;
    private final ScipOccurrenceRepository occurrenceRepo;
    private final ScipSymbolInfoRepository symbolInfoRepo;

    // SCIP field numbers
    private static final int INDEX_DOCUMENTS = 3;
    private static final int INDEX_EXTERNAL_SYMBOLS = 4;
    private static final int DOC_RELATIVE_PATH = 1;
    private static final int DOC_LANGUAGE = 4;
    private static final int DOC_OCCURRENCES = 5;
    private static final int DOC_SYMBOLS = 6;
    private static final int OCC_SYMBOL = 1;
    private static final int OCC_RANGE = 3;
    private static final int OCC_ROLES = 4;
    private static final int SYM_SYMBOL = 1;
    private static final int SYM_DOCUMENTATION = 3;
    private static final int SYM_DISPLAY_NAME = 7;

    @Transactional
    public void ingest(Long userId, String repoUrl, InputStream scipBinary) throws IOException {
        log.info("Starting SCIP ingestion for user={} repo={}", userId, repoUrl);

        CodedInputStream stream = CodedInputStream.newInstance(scipBinary);
        int tag;

        while ((tag = stream.readTag()) != 0) {
            int fieldNumber = tag >>> 3;
            int wireType = tag & 0x7;

            if (wireType == 2) { // length-delimited
                byte[] bytes = stream.readByteArray();
                if (fieldNumber == INDEX_DOCUMENTS) {
                    ingestDocument(userId, repoUrl, CodedInputStream.newInstance(bytes));
                } else if (fieldNumber == INDEX_EXTERNAL_SYMBOLS) {
                    ingestSymbolInfo(userId, repoUrl, CodedInputStream.newInstance(bytes));
                } else {
                    // skip unknown fields
                }
            } else {
                stream.skipField(tag);
            }
        }

        log.info("SCIP ingestion complete for user={} repo={}", userId, repoUrl);
    }

    private void ingestDocument(Long userId, String repoUrl, CodedInputStream doc) throws IOException {
        String relativePath = null;
        String language = null;
        List<byte[]> occurrenceBytes = new ArrayList<>();
        List<byte[]> symbolBytes = new ArrayList<>();

        int tag;
        while ((tag = doc.readTag()) != 0) {
            int field = tag >>> 3;
            if ((tag & 0x7) == 2) {
                byte[] bytes = doc.readByteArray();
                if (field == DOC_RELATIVE_PATH)
                    relativePath = new String(bytes);
                else if (field == DOC_LANGUAGE)
                    language = new String(bytes);
                else if (field == DOC_OCCURRENCES)
                    occurrenceBytes.add(bytes);
                else if (field == DOC_SYMBOLS)
                    symbolBytes.add(bytes);
            } else {
                doc.skipField(tag);
            }
        }

        if (relativePath == null)
            return;

        // Upsert document
        final String finalRelativePath = relativePath;
        final String finalLanguage = language;
        ScipDocument document = documentRepo
                .findByUserIdAndRepoUrlAndRelativeUri(userId, repoUrl, finalRelativePath)
                .orElseGet(() -> ScipDocument.builder()
                        .userId(userId)
                        .repoUrl(repoUrl)
                        .relativeUri(finalRelativePath)
                        .build());
        document.setLanguage(finalLanguage);
        document = documentRepo.save(document);

        // Replace occurrences for this document
        occurrenceRepo.deleteByDocumentId(document.getId());
        List<ScipOccurrence> occurrences = new ArrayList<>();
        for (byte[] occ : occurrenceBytes) {
            ScipOccurrence o = parseOccurrence(document.getId(), CodedInputStream.newInstance(occ));
            if (o != null)
                occurrences.add(o);
        }
        occurrenceRepo.saveAll(occurrences);

        // Upsert inline symbol info
        for (byte[] sym : symbolBytes) {
            ingestSymbolInfo(userId, repoUrl, CodedInputStream.newInstance(sym));
        }
    }

    private ScipOccurrence parseOccurrence(Long documentId, CodedInputStream occ) throws IOException {
        String symbol = null;
        List<Integer> range = new ArrayList<>();
        int roleFlags = 0;

        int tag;
        while ((tag = occ.readTag()) != 0) {
            int field = tag >>> 3;
            int wire = tag & 0x7;
            if (wire == 2 && field == OCC_SYMBOL) {
                symbol = new String(occ.readByteArray());
            } else if (wire == 2 && field == OCC_RANGE) {
                // packed int32 array
                CodedInputStream packed = CodedInputStream.newInstance(occ.readByteArray());
                while (!packed.isAtEnd())
                    range.add(packed.readInt32());
            } else if (wire == 0 && field == OCC_ROLES) {
                roleFlags = occ.readInt32();
            } else {
                occ.skipField(tag);
            }
        }

        if (symbol == null || range.size() < 4)
            return null;

        return ScipOccurrence.builder()
                .documentId(documentId)
                .symbol(symbol)
                .startLine(range.get(0))
                .startChar(range.get(1))
                .endLine(range.get(2))
                .endChar(range.get(3))
                .roleFlags(roleFlags)
                .build();
    }

    private void ingestSymbolInfo(Long userId, String repoUrl, CodedInputStream sym) throws IOException {
        String symbol = null;
        String displayName = null;
        StringBuilder docs = new StringBuilder();

        int tag;
        while ((tag = sym.readTag()) != 0) {
            int field = tag >>> 3;
            if ((tag & 0x7) == 2) {
                byte[] bytes = sym.readByteArray();
                if (field == SYM_SYMBOL)
                    symbol = new String(bytes);
                else if (field == SYM_DISPLAY_NAME)
                    displayName = new String(bytes);
                else if (field == SYM_DOCUMENTATION) {
                    if (docs.length() > 0)
                        docs.append("\n\n");
                    docs.append(new String(bytes));
                }
            } else {
                sym.skipField(tag);
            }
        }

        if (symbol == null)
            return;

        final String finalSymbol = symbol;
        final String finalDisplayName = displayName;
        final String finalDocs = docs.toString();

        ScipSymbolInfo info = symbolInfoRepo
                .findByUserIdAndRepoUrlAndSymbol(userId, repoUrl, finalSymbol)
                .orElseGet(() -> ScipSymbolInfo.builder()
                        .userId(userId)
                        .repoUrl(repoUrl)
                        .symbol(finalSymbol)
                        .build());
        if (finalDisplayName != null)
            info.setDisplayName(finalDisplayName);
        if (!finalDocs.isEmpty())
            info.setDocumentation(finalDocs);
        symbolInfoRepo.save(info);
    }
}
