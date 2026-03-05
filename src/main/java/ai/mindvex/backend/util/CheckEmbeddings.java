package ai.mindvex.backend.util;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

public class CheckEmbeddings {
  public static void main(String[] args) {
    String dbUrl = System.getenv("DATABASE_URL");
    try (Connection conn = DriverManager.getConnection(dbUrl);
        Statement stmt = conn.createStatement()) {

      System.out.println("📊 EMBEDDINGS STATUS:\n");

      // Check embeddings
      ResultSet rs = stmt.executeQuery(
          "SELECT COUNT(*) as total FROM code_intelligence.vector_embeddings");
      if (rs.next()) {
        int count = rs.getInt("total");
        if (count > 0) {
          System.out.println("✅ Vector embeddings exist: " + count + " chunks");
        } else {
          System.out.println("⚠️  NO EMBEDDINGS FOUND");
          System.out.println("   The vector_embeddings table is empty.");
        }
      }

      // Check recent jobs
      System.out.println("\n📋 RECENT GRAPH_BUILD JOBS:\n");
      rs = stmt.executeQuery(
          "SELECT id, status, created_at FROM public.index_jobs WHERE job_type='graph_build' ORDER BY id DESC LIMIT 5");
      boolean hasJobs = false;
      while (rs.next()) {
        hasJobs = true;
        System.out.printf("   Job #%d: [%s] at %s%n",
            rs.getInt("id"),
            rs.getString("status"),
            rs.getTimestamp("created_at"));
      }
      if (!hasJobs) {
        System.out.println("   ⚠️  NO GRAPH_BUILD JOBS FOUND");
        System.out.println("   No /api/graph/build API calls have been made yet.");
      }

    } catch (Exception e) {
      System.err.println("ERROR: " + e.getMessage());
    }
  }
}
