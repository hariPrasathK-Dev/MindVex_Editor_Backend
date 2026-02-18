package ai.mindvex.backend.service;

import ai.mindvex.backend.dto.BlameLineResponse;
import ai.mindvex.backend.dto.CommitFileDiff;
import ai.mindvex.backend.entity.CommitStat;
import ai.mindvex.backend.repository.CommitStatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.BlameCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.blame.BlameResult;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.util.io.DisabledOutputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;

/**
 * JGitMiningService
 *
 * Clones or reuses a cached bare repository and traverses its commit history
 * using JGit's RevWalk + DiffFormatter to extract per-file line change counts.
 *
 * Repos are stored in: <repoBaseDir>/<sha256(repoUrl)>/
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class JGitMiningService {

    private final CommitStatRepository commitStatRepository;

    @Value("${app.git.repo-base-dir:${java.io.tmpdir}/mindvex-repos}")
    private String repoBaseDir;

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * Mine commit history for a repository since the given date.
     *
     * @param userId      owner user ID (for DB scoping)
     * @param repoUrl     HTTPS clone URL
     * @param accessToken GitHub personal access token (may be null for public
     *                    repos)
     * @param since       only mine commits after this instant (null = all history)
     * @return list of per-file diffs across all qualifying commits
     */
    public List<CommitFileDiff> mineHistory(
            Long userId,
            String repoUrl,
            String accessToken,
            Instant since) throws Exception {

        File repoDir = getRepoDir(repoUrl);
        Git git = cloneOrOpen(repoUrl, accessToken, repoDir);

        List<CommitFileDiff> diffs = new ArrayList<>();

        try (Repository repo = git.getRepository();
                RevWalk walk = new RevWalk(repo)) {

            walk.markStart(walk.parseCommit(repo.resolve("HEAD")));

            for (RevCommit commit : walk) {
                Instant committedAt = commit.getAuthorIdent().getWhen().toInstant();
                if (since != null && committedAt.isBefore(since))
                    continue;

                List<CommitFileDiff> fileDiffs = diffCommit(repo, commit, committedAt);
                diffs.addAll(fileDiffs);

                // Persist raw commit record (upsert via unique constraint)
                persistCommitStat(userId, repoUrl, commit, fileDiffs);
            }
        } finally {
            git.close();
        }

        log.info("[JGit] Mined {} file-diffs from {}", diffs.size(), repoUrl);
        return diffs;
    }

    // ─── Clone / Open ─────────────────────────────────────────────────────────

    private Git cloneOrOpen(String repoUrl, String accessToken, File repoDir) throws Exception {
        if (repoDir.exists() && new File(repoDir, "HEAD").exists()) {
            log.info("[JGit] Reusing cached repo at {}", repoDir);
            Git git = Git.open(repoDir);
            // Fetch latest commits
            if (accessToken != null) {
                git.fetch()
                        .setCredentialsProvider(credentials(accessToken))
                        .call();
            } else {
                git.fetch().call();
            }
            return git;
        }

        log.info("[JGit] Cloning {} → {}", repoUrl, repoDir);
        repoDir.mkdirs();

        var cloneCmd = Git.cloneRepository()
                .setURI(repoUrl)
                .setDirectory(repoDir)
                .setBare(true) // bare clone — no working tree, saves disk
                .setCloneAllBranches(false);

        if (accessToken != null) {
            cloneCmd.setCredentialsProvider(credentials(accessToken));
        }

        return cloneCmd.call();
    }

    private UsernamePasswordCredentialsProvider credentials(String token) {
        return new UsernamePasswordCredentialsProvider("oauth2", token);
    }

    // ─── Diff a single commit ─────────────────────────────────────────────────

    private List<CommitFileDiff> diffCommit(
            Repository repo,
            RevCommit commit,
            Instant committedAt) throws IOException {

        List<CommitFileDiff> results = new ArrayList<>();

        try (DiffFormatter df = new DiffFormatter(DisabledOutputStream.INSTANCE);
                ObjectReader reader = repo.newObjectReader()) {

            df.setRepository(repo);
            df.setDiffComparator(RawTextComparator.WS_IGNORE_ALL);
            df.setDetectRenames(true);

            AbstractTreeIterator oldTree = commit.getParentCount() > 0
                    ? treeParser(repo, reader, commit.getParent(0))
                    : new EmptyTreeIterator();
            AbstractTreeIterator newTree = treeParser(repo, reader, commit);

            List<DiffEntry> entries = df.scan(oldTree, newTree);

            for (DiffEntry entry : entries) {
                String path = entry.getChangeType() == DiffEntry.ChangeType.DELETE
                        ? entry.getOldPath()
                        : entry.getNewPath();

                int added = 0, deleted = 0;
                for (Edit edit : df.toFileHeader(entry).toEditList()) {
                    added += edit.getEndB() - edit.getBeginB();
                    deleted += edit.getEndA() - edit.getBeginA();
                }

                if (added + deleted == 0)
                    continue; // skip no-op entries

                results.add(new CommitFileDiff(
                        commit.getName(),
                        path,
                        committedAt,
                        commit.getAuthorIdent().getEmailAddress(),
                        added,
                        deleted));
            }
        }

        return results;
    }

    private AbstractTreeIterator treeParser(
            Repository repo,
            ObjectReader reader,
            RevCommit commit) throws IOException {
        var treeId = commit.getTree().getId();
        var parser = new CanonicalTreeParser();
        parser.reset(reader, treeId);
        return parser;
    }

    // ─── Persist raw commit stats ─────────────────────────────────────────────

    private void persistCommitStat(
            Long userId,
            String repoUrl,
            RevCommit commit,
            List<CommitFileDiff> fileDiffs) {
        int totalAdded = fileDiffs.stream().mapToInt(CommitFileDiff::linesAdded).sum();
        int totalDeleted = fileDiffs.stream().mapToInt(CommitFileDiff::linesDeleted).sum();

        // Upsert — ignore if already recorded (unique constraint on
        // user_id+repo_url+commit_hash)
        if (!commitStatRepository.existsByUserIdAndRepoUrlAndCommitHash(
                userId, repoUrl, commit.getName())) {

            CommitStat stat = new CommitStat();
            stat.setUserId(userId);
            stat.setRepoUrl(repoUrl);
            stat.setCommitHash(commit.getName());
            stat.setAuthorEmail(commit.getAuthorIdent().getEmailAddress());
            stat.setMessage(commit.getShortMessage());
            stat.setCommittedAt(commit.getAuthorIdent().getWhen().toInstant());
            stat.setFilesChanged(fileDiffs.size());
            stat.setInsertions(totalAdded);
            stat.setDeletions(totalDeleted);
            commitStatRepository.save(stat);
        }
    }

    // ─── Blame ────────────────────────────────────────────────────────────────

    /**
     * Run git blame on a file and return per-line annotations.
     * The repo must have been cloned previously via mineHistory().
     */
    public List<BlameLineResponse> blame(
            String repoUrl,
            String accessToken,
            String filePath) throws Exception {
        File repoDir = getRepoDir(repoUrl);
        if (!repoDir.exists()) {
            throw new IllegalStateException("Repository not yet cloned. Run /api/analytics/mine first.");
        }

        List<BlameLineResponse> lines = new ArrayList<>();

        try (Git git = Git.open(repoDir);
                Repository repo = git.getRepository()) {

            BlameCommand blameCmd = new BlameCommand(repo);
            blameCmd.setFilePath(filePath);
            BlameResult result = blameCmd.call();

            if (result == null)
                return lines;

            int lineCount = result.getResultContents().size();
            for (int i = 0; i < lineCount; i++) {
                RevCommit commit = result.getSourceCommit(i);
                String hash = commit != null ? commit.getName() : "unknown";
                String author = commit != null ? commit.getAuthorIdent().getEmailAddress() : "";
                String committedAt = commit != null
                        ? commit.getAuthorIdent().getWhen().toInstant().toString()
                        : "";
                String content = result.getResultContents().getString(i);

                lines.add(new BlameLineResponse(i + 1, hash, author, committedAt, content));
            }
        }

        log.info("[Blame] {} lines for {}/{}", lines.size(), repoUrl, filePath);
        return lines;
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private File getRepoDir(String repoUrl) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(repoUrl.getBytes(StandardCharsets.UTF_8));
        String dirName = HexFormat.of().formatHex(hash).substring(0, 16);
        return new File(repoBaseDir, dirName);
    }
}
