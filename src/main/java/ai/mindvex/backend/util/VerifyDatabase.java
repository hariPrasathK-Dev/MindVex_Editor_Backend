package ai.mindvex.backend.util;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Quick verification of database state.
 * Run: mvn compile exec:java
 * -Dexec.mainClass="ai.mindvex.backend.util.VerifyDatabase"
 */
public class VerifyDatabase {

  public static void main(String[] args) {
    String dbUrl = System.getenv("DATABASE_URL");
    if (dbUrl == null || dbUrl.isEmpty()) {
      System.err.println("ERROR: DATABASE_URL not set!");
      System.exit(1);
    }

    System.out.println("╔════════════════════════════════════════════════════════════╗");
    System.out.println("║          MindVex Database Verification                    ║");
    System.out.println("╚════════════════════════════════════════════════════════════╝\n");

    try (Connection conn = DriverManager.getConnection(dbUrl);
        Statement stmt = conn.createStatement()) {

      // Check Users
      System.out.println("👥 USERS:");
      try (ResultSet rs = stmt.executeQuery(
          "SELECT COUNT(*) as total FROM users")) {
        if (rs.next()) {
          System.out.printf("   Total Users: %d%n%n", rs.getInt("total"));
        }
      }

      // Check Repository History
      System.out.println("📦 REPOSITORY HISTORY:");
      try (ResultSet rs = stmt.executeQuery(
          "SELECT COUNT(*) as total, COUNT(DISTINCT repo_url) as unique_repos FROM repository_history")) {
        if (rs.next()) {
          System.out.printf("   Total Records: %d | Unique Repos: %d%n",
              rs.getInt("total"), rs.getInt("unique_repos"));
        }
      }
      try (ResultSet rs = stmt.executeQuery(
          "SELECT repo_url FROM repository_history ORDER BY created_at DESC LIMIT 3")) {
        System.out.println("   Recent repos:");
        while (rs.next()) {
          System.out.printf("   - %s%n", rs.getString(1));
        }
        System.out.println();
      }

      // Check Index Jobs
      System.out.println("⚙️  INDEX JOBS:");
      try (ResultSet rs = stmt.executeQuery(
          "SELECT job_type, status, COUNT(*) as count FROM public.index_jobs GROUP BY job_type, status ORDER BY job_type, status")) {
        while (rs.next()) {
          System.out.printf("   %s [%s]: %d jobs%n",
              rs.getString("job_type"),
              rs.getString("status"),
              rs.getInt("count"));
        }
      }
      try (ResultSet rs = stmt.executeQuery(
          "SELECT id, job_type, status, repo_url FROM public.index_jobs ORDER BY created_at DESC LIMIT 5")) {
        System.out.println("\n   Recent jobs:");
        while (rs.next()) {
          String repoShort = rs.getString("repo_url");
          if (repoShort != null && repoShort.length() > 50) {
            repoShort = repoShort.substring(repoShort.lastIndexOf('/') + 1);
          }
          System.out.printf("   #%d: %s [%s] - %s%n",
              rs.getInt("id"),
              rs.getString("job_type"),
              rs.getString("status"),
              repoShort);
        }
        System.out.println();
      }

      // Check File Dependencies
      System.out.println("🔗 FILE DEPENDENCIES:");
      try (ResultSet rs = stmt.executeQuery(
          "SELECT COUNT(*) as total, COUNT(DISTINCT repo_url) as repos, COUNT(DISTINCT source_file) as files FROM code_graph.file_dependencies")) {
        if (rs.next()) {
          System.out.printf("   Total Edges: %d | Repos: %d | Unique Files: %d%n%n",
              rs.getInt("total"), rs.getInt("repos"), rs.getInt("files"));
        }
      }

      // Check Vector Embeddings - THE CRITICAL ONE!
      System.out.println("🧠 VECTOR EMBEDDINGS:");
      try (ResultSet rs = stmt.executeQuery(
          "SELECT COUNT(*) as total, COUNT(DISTINCT repo_url) as repos, COUNT(DISTINCT file_path) as files FROM code_intelligence.vector_embeddings")) {
        if (rs.next()) {
          int total = rs.getInt("total");
          int repos = rs.getInt("repos");
          int files = rs.getInt("files");

          if (total > 0) {
            System.out.printf("   ✅ Total Chunks: %d | Repos: %d | Files: %d%n", total, repos, files);
          } else {
            System.out.println("   ⚠️  NO EMBEDDINGS FOUND!");
            System.out.println("   → This means graph_build jobs haven't generated embeddings yet");
          }
        }
      }
      try (ResultSet rs = stmt.executeQuery(
          "SELECT repo_url, COUNT(*) as chunks FROM code_intelligence.vector_embeddings GROUP BY repo_url LIMIT 5")) {
        if (rs.next()) {
          System.out.println("\n   Embeddings by repo:");
          do {
            String repo = rs.getString("repo_url");
            if (repo.length() > 50) {
              repo = repo.substring(repo.lastIndexOf('/') + 1);
            }
            System.out.printf("   - %s: %d chunks%n", repo, rs.getInt("chunks"));
          } while (rs.next());
        }
        System.out.println();
      }

      // Check Wiki Summaries
      System.out.println("📝 WIKI SUMMARIES:");
      try (ResultSet rs = stmt.executeQuery(
          "SELECT COUNT(*) as total, COUNT(DISTINCT repo_url) as repos FROM code_intelligence.wiki_summaries")) {
        if (rs.next()) {
          System.out.printf("   Total: %d | Repos: %d%n%n",
              rs.getInt("total"), rs.getInt("repos"));
        }
      }

      // Summary
      System.out.println("════════════════════════════════════════════════════════════");
      System.out.println("✅ Database connection successful!");
      System.out.println("✅ All required tables exist");

      try (ResultSet rs = stmt.executeQuery(
          "SELECT COUNT(*) as count FROM code_intelligence.vector_embeddings")) {
        if (rs.next() && rs.getInt("count") > 0) {
          System.out.println("✅ Embeddings are being generated - SYSTEM WORKING!");
        } else {
          System.out.println("⚠️  No embeddings yet - trigger a graph_build job");
        }
      }
      System.out.println("════════════════════════════════════════════════════════════\n");

    } catch (Exception e) {
      System.err.println("\n❌ ERROR: " + e.getMessage());
      e.printStackTrace();
      System.exit(1);
    }
  }
}
