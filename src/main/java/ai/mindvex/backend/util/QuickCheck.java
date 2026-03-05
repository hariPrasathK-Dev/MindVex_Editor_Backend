package ai.mindvex.backend.util;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Simple database status check.
 */
public class QuickCheck {
  public static void main(String[] args) {
    String dbUrl = System.getenv("DATABASE_URL");
    if (dbUrl == null || dbUrl.isEmpty()) {
      System.err.println("ERROR: DATABASE_URL not set!");
      System.exit(1);
    }

    System.out.println("╔════════════════════════════════════════╗");
    System.out.println("║     Quick Database Status Check       ║");
    System.out.println("╚════════════════════════════════════════╝\n");

    try (Connection conn = DriverManager.getConnection(dbUrl);
        Statement stmt = conn.createStatement()) {

      // Simple row counts
      System.out.println("📊 ROW COUNTS:");
      String[] queries = {
          "SELECT 'Users' as table_name, COUNT(*) as rows FROM users",
          "SELECT 'Index Jobs' as table_name, COUNT(*) as rows FROM public.index_jobs",
          "SELECT 'File Dependencies' as table_name, COUNT(*) as rows FROM code_graph.file_dependencies",
          "SELECT 'Vector Embeddings' as table_name, COUNT(*) as rows FROM code_intelligence.vector_embeddings",
          "SELECT 'Wiki Summaries' as table_name, COUNT(*) as rows FROM code_intelligence.wiki_summaries"
      };

      for (String query : queries) {
        try (ResultSet rs = stmt.executeQuery(query)) {
          if (rs.next()) {
            String table = rs.getString(1);
            int count = rs.getInt(2);
            String status = count > 0 ? "✅" : "⚠️ ";
            System.out.printf("   %s %-25s %6d rows%n", status, table, count);
          }
        }
      }

      System.out.println("\n📋 RECENT JOBS (Last 5):");
      try (ResultSet rs = stmt.executeQuery(
          "SELECT id, job_type, status, created_at FROM public.index_jobs ORDER BY id DESC LIMIT 5")) {
        while (rs.next()) {
          System.out.printf("   #%-3d %-15s [%-10s] %s%n",
              rs.getInt("id"),
              rs.getString("job_type"),
              rs.getString("status"),
              rs.getTimestamp("created_at"));
        }
      }

      System.out.println("\n🧠 EMBEDDINGS DETAILS:");
      try (ResultSet rs = stmt.executeQuery(
          "SELECT COUNT(*) as total, COUNT(DISTINCT user_id) as users, COUNT(DISTINCT file_path) as files FROM code_intelligence.vector_embeddings")) {
        if (rs.next()) {
          int total = rs.getInt("total");
          if (total > 0) {
            System.out.printf("   ✅ %d embedding chunks across %d files%n",
                total, rs.getInt("files"));
          } else {
            System.out.println("   ⚠️  NO EMBEDDINGS YET");
            System.out.println("   → Trigger a graph_build job to generate them");
          }
        }
      }

      System.out.println("\n════════════════════════════════════════");
      try (ResultSet rs = stmt.executeQuery(
          "SELECT COUNT(*) as count FROM code_intelligence.vector_embeddings")) {
        if (rs.next() && rs.getInt("count") > 0) {
          System.out.println("✅ SYSTEM STATUS: WORKING!");
          System.out.println("   Embeddings are being generated");
        } else {
          System.out.println("⚠️  SYSTEM STATUS: READY BUT WAITING");
          System.out.println("   Table exists, waiting for first graph_build");
        }
      }
      System.out.println("════════════════════════════════════════\n");

    } catch (Exception e) {
      System.err.println("\n❌ ERROR: " + e.getMessage());
      e.printStackTrace();
    }
  }
}
