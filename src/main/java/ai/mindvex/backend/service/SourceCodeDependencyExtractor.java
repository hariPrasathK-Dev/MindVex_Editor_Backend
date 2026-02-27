package ai.mindvex.backend.service;

import ai.mindvex.backend.entity.FileDependency;
import ai.mindvex.backend.repository.FileDependencyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Lightweight import-based dependency extractor.
 *
 * Clones the repo, walks all source files, extracts import/require
 * statements using regex, resolves them to relative paths, and saves
 * them as FileDependency edges.
 *
 * Supports: TypeScript, JavaScript, Python, Java, Go, Kotlin, Rust, C#.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SourceCodeDependencyExtractor {

    private final FileDependencyRepository depRepo;

    // Regex patterns for import extraction per language family
    private static final Pattern JS_IMPORT = Pattern.compile(
            "(?:import\\s+.*?from\\s+['\"]([^'\"]+)['\"])|(?:require\\s*\\(\\s*['\"]([^'\"]+)['\"]\\s*\\))"
    );
    private static final Pattern PYTHON_IMPORT = Pattern.compile(
            "(?:from\\s+(\\S+)\\s+import)|(?:import\\s+(\\S+))"
    );
    private static final Pattern JAVA_IMPORT = Pattern.compile(
            "import\\s+(?:static\\s+)?(\\S+);"
    );
    private static final Pattern GO_IMPORT = Pattern.compile(
            "\"([^\"]+)\""
    );

    private static final Set<String> SOURCE_EXTENSIONS = Set.of(
            ".ts", ".tsx", ".js", ".jsx", ".mjs", ".cjs",
            ".py", ".java", ".kt", ".go", ".rs", ".cs",
            ".cpp", ".cc", ".c", ".h", ".hpp"
    );

    private static final Set<String> SKIP_DIRS = Set.of(
            "node_modules", ".git", "dist", "build", ".cache",
            ".next", "target", "__pycache__", ".gradle", "vendor"
    );

    /**
     * Clone the repo, extract import-based file dependencies, and save them.
     *
     * @return number of dependency edges saved
     */
    @Transactional
    public int extractFromRepo(Long userId, String repoUrl) throws IOException {
        log.info("[SourceCodeDepExtractor] Starting extraction for user={} repo={}", userId, repoUrl);

        // Clone the repo to a temp directory
        Path tempDir = Files.createTempDirectory("mindvex-graph-");

        try {
            // Normalize and validate the incoming repo URL
            String normalizedUrl = normalizeRepoUrl(repoUrl);
            log.info("[SourceCodeDepExtractor] Normalized repo URL: {}", normalizedUrl);
            log.info("[SourceCodeDepExtractor] Cloning {} into {}", normalizedUrl, tempDir);
            Git.cloneRepository()
                    .setURI(normalizedUrl)
                    .setDirectory(tempDir.toFile())
                    .setDepth(1) // shallow clone for speed
                    .call();

            // Collect all source files
            Map<String, Path> sourceFiles = new LinkedHashMap<>();
            Files.walkFileTree(tempDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (SKIP_DIRS.contains(dir.getFileName().toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String fileName = file.getFileName().toString();
                    String ext = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf('.')) : "";
                    if (SOURCE_EXTENSIONS.contains(ext.toLowerCase())) {
                        // Store relative path from repo root
                        String relativePath = tempDir.relativize(file).toString().replace('\\', '/');
                        sourceFiles.put(relativePath, file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });

            log.info("[SourceCodeDepExtractor] Found {} source files in {}", sourceFiles.size(), repoUrl);

            // Delete stale edges for this user+repo
            depRepo.deleteByUserIdAndRepoUrl(userId, repoUrl);

            // Extract dependencies
            List<FileDependency> allEdges = new ArrayList<>();
            Set<String> filePathSet = sourceFiles.keySet();

            for (Map.Entry<String, Path> entry : sourceFiles.entrySet()) {
                String relativePath = entry.getKey();
                Path filePath = entry.getValue();

                try {
                    String content = Files.readString(filePath);
                    List<String> imports = extractImports(relativePath, content);

                    for (String importPath : imports) {
                        // Try to resolve the import to an actual file in the repo
                        String resolved = resolveImport(relativePath, importPath, filePathSet);
                        if (resolved != null && !resolved.equals(relativePath)) {
                            allEdges.add(new FileDependency(
                                    userId, repoUrl, relativePath, resolved, "import"
                            ));
                        }
                    }
                } catch (Exception e) {
                    log.debug("[SourceCodeDepExtractor] Could not parse {}: {}", relativePath, e.getMessage());
                }
            }

            // Deduplicate edges
            Set<String> seen = new HashSet<>();
            List<FileDependency> uniqueEdges = allEdges.stream()
                    .filter(e -> seen.add(e.getSourceFile() + "→" + e.getTargetFile()))
                    .collect(Collectors.toList());

            depRepo.saveAll(uniqueEdges);
            log.info("[SourceCodeDepExtractor] Saved {} unique edges for {}", uniqueEdges.size(), repoUrl);

            return uniqueEdges.size();

        } catch (Exception e) {
            log.error("[SourceCodeDepExtractor] Failed to extract from {}: {}", repoUrl, e.getMessage(), e);
            throw new IOException("Failed to extract dependencies: " + e.getMessage(), e);
        } finally {
            // Clean up temp directory
            deleteRecursively(tempDir);
        }
    }

    /**
     * Extract import paths from file content based on file extension.
     */
    private List<String> extractImports(String filePath, String content) {
        List<String> imports = new ArrayList<>();
        String ext = filePath.substring(filePath.lastIndexOf('.'));

        switch (ext) {
            case ".ts", ".tsx", ".js", ".jsx", ".mjs", ".cjs" -> {
                Matcher m = JS_IMPORT.matcher(content);
                while (m.find()) {
                    String match = m.group(1) != null ? m.group(1) : m.group(2);
                    if (match != null && match.startsWith(".")) {
                        imports.add(match);
                    }
                }
            }
            case ".py" -> {
                Matcher m = PYTHON_IMPORT.matcher(content);
                while (m.find()) {
                    String match = m.group(1) != null ? m.group(1) : m.group(2);
                    if (match != null) {
                        imports.add(match.replace(".", "/"));
                    }
                }
            }
            case ".java", ".kt" -> {
                Matcher m = JAVA_IMPORT.matcher(content);
                while (m.find()) {
                    String match = m.group(1);
                    if (match != null) {
                        // Convert package path to file path
                        imports.add(match.replace(".", "/"));
                    }
                }
            }
            case ".go" -> {
                // Go imports inside import blocks
                boolean inImportBlock = false;
                for (String line : content.split("\n")) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("import (")) {
                        inImportBlock = true;
                        continue;
                    }
                    if (inImportBlock && trimmed.equals(")")) {
                        inImportBlock = false;
                        continue;
                    }
                    if (inImportBlock || trimmed.startsWith("import \"")) {
                        Matcher m = GO_IMPORT.matcher(trimmed);
                        if (m.find()) {
                            imports.add(m.group(1));
                        }
                    }
                }
            }
        }

        return imports;
    }

    /**
     * Try to resolve an import path to a real file in the repo.
     */
    private String resolveImport(String sourceFile, String importPath, Set<String> allFiles) {
        String sourceDir = sourceFile.contains("/")
                ? sourceFile.substring(0, sourceFile.lastIndexOf('/'))
                : "";

        // For relative imports (./foo, ../bar)
        String resolved;
        if (importPath.startsWith("./") || importPath.startsWith("../")) {
            resolved = normalizePath(sourceDir + "/" + importPath);
        } else {
            // For absolute imports, try matching against file paths
            resolved = importPath;
        }

        // Try direct match
        if (allFiles.contains(resolved)) return resolved;

        // Try with common extensions
        for (String ext : List.of(".ts", ".tsx", ".js", ".jsx", ".py", ".java", ".kt", ".go")) {
            if (allFiles.contains(resolved + ext)) return resolved + ext;
        }

        // Try as directory with index file
        for (String idx : List.of("/index.ts", "/index.tsx", "/index.js", "/index.jsx")) {
            if (allFiles.contains(resolved + idx)) return resolved + idx;
        }

        // For Java/Kotlin: try matching last segment
        String lastSegment = importPath.contains("/")
                ? importPath.substring(importPath.lastIndexOf('/') + 1)
                : importPath;

        for (String file : allFiles) {
            String baseName = file.contains("/") ? file.substring(file.lastIndexOf('/') + 1) : file;
            String nameWithoutExt = baseName.contains(".") ? baseName.substring(0, baseName.lastIndexOf('.')) : baseName;
            if (nameWithoutExt.equals(lastSegment)) {
                return file;
            }
        }

        return null;
    }

    private String normalizePath(String path) {
        Path p = Path.of(path).normalize();
        return p.toString().replace('\\', '/');
    }

    private void deleteRecursively(Path dir) {
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("[SourceCodeDepExtractor] Failed to clean up temp dir: {}", dir, e);
        }
    }

    /**
     * Normalize and validate a repository URL. Accepts https and git SSH forms.
     */
    private String normalizeRepoUrl(String repoUrl) throws IOException {
        if (repoUrl == null || repoUrl.isBlank()) {
            throw new IOException("Invalid repository URL: empty");
        }

        String u = repoUrl.trim();
        // strip trailing slash
        if (u.endsWith("/")) u = u.substring(0, u.length() - 1);

        // Accept https://github.com/owner/repo or git@github.com:owner/repo(.git)
        boolean httpsForm = u.matches("(?i)^https?://github\\.com/[^/]+/[^/]+(?:\\.git)?$");
        boolean sshForm = u.matches("(?i)^git@github\\.com:[^/]+/[^/]+(?:\\.git)?$");

        if (!httpsForm && !sshForm) {
            throw new IOException("Invalid repository URL: expected https://github.com/{owner}/{repo}");
        }

        return u;
    }
}
