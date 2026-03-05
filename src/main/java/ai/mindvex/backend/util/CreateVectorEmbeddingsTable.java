package ai.mindvex.backend.util;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/**
 * Utility to create the vector_embeddings table manually.
 * Run this if Flyway migration V15 didn't apply correctly.
 * 
 * Usage: mvn compile exec:java
 * -Dexec.mainClass="ai.mindvex.backend.util.CreateVectorEmbeddingsTable"
 */
public class CreateVectorEmbeddingsTable {

  public static void main(String[] args) {
    // Railway database connection from environment or hardcoded
    String dbUrl = System.getenv("DATABASE_URL");
    if (dbUrl == null || dbUrl.isEmpty()) {
      System.err.println("ERROR: DATABASE_URL environment variable not set!");
      System.err.println("Set it with:");
      System.err.println(
          "$env:DATABASE_URL = \"jdbc:postgresql://crossover.proxy.rlwy.net:47666/railway?user=postgres&password=YOUR_PASSWORD\"");
      System.exit(1);
    }

    System.out.println("Connecting to database...");
    System.out.println("URL: " + maskPassword(dbUrl));

    try (Connection conn = DriverManager.getConnection(dbUrl)) {
      System.out.println("✅ Connected successfully!");

      // Create schema
      System.out.println("\n📦 Creating schema 'code_intelligence'...");
      try (Statement stmt = conn.createStatement()) {
        stmt.execute("CREATE SCHEMA IF NOT EXISTS code_intelligence");
        System.out.println("✅ Schema created/verified");
      }

      // Create table
      System.out.println("\n📋 Creating table 'vector_embeddings'...");
      String createTableSQL = """
          CREATE TABLE IF NOT EXISTS code_intelligence.vector_embeddings (
              id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
              user_id           BIGINT          NOT NULL,
              repo_url          VARCHAR(1000)   NOT NULL,
              file_path         VARCHAR(2000)   NOT NULL,
              chunk_index       INT             NOT NULL DEFAULT 0,
              chunk_text        TEXT            NOT NULL,
              embedding         TEXT,
              created_at        TIMESTAMP       NOT NULL DEFAULT now(),
              CONSTRAINT uq_embedding_chunk UNIQUE (user_id, repo_url, file_path, chunk_index)
          )
          """;

      try (Statement stmt = conn.createStatement()) {
        stmt.execute(createTableSQL);
        System.out.println("✅ Table created successfully!");
      }

      // Verify table exists
      System.out.println("\n🔍 Verifying table...");
      try (Statement stmt = conn.createStatement();
          var rs = stmt.executeQuery("SELECT COUNT(*) FROM code_intelligence.vector_embeddings")) {
        if (rs.next()) {
          int count = rs.getInt(1);
          System.out.println("✅ Table verified! Current row count: " + count);
        }
      }

      // Show table structure
      System.out.println("\n📊 Table structure:");
      try (Statement stmt = conn.createStatement();
          var rs = stmt.executeQuery("""
              SELECT column_name, data_type, character_maximum_length, is_nullable
              FROM information_schema.columns
              WHERE table_schema = 'code_intelligence'
                AND table_name = 'vector_embeddings'
              ORDER BY ordinal_position
              """)) {
        System.out.println("┌─────────────────┬─────────────────┬──────────┬──────────┐");
        System.out.println("│ Column          │ Type            │ Length   │ Nullable │");
        System.out.println("├─────────────────┼─────────────────┼──────────┼──────────┤");
        while (rs.next()) {
          String col = rs.getString(1);
          String type = rs.getString(2);
          Object len = rs.getObject(3);
          String nullable = rs.getString(4);
          System.out.printf("│ %-15s │ %-15s │ %-8s │ %-8s │%n",
              col, type, len != null ? len : "N/A", nullable);
        }
        System.out.println("└─────────────────┴─────────────────┴──────────┴──────────┘");
      }

      System.out.println("\n✅ SUCCESS! Table is ready to use.");
      System.out.println("\nNext steps:");
      System.out.println("1. Deploy latest backend code to Render");
      System.out.println("2. Import a repository");
      System.out.println("3. Click refresh in Quick Actions");
      System.out.println("4. Check logs for '[EmbeddingIngestion] Ingested X chunks'");

    } catch (Exception e) {
      System.err.println("\n❌ ERROR: " + e.getMessage());
      e.printStackTrace();
      System.exit(1);
    }
  }

  private static String maskPassword(String url) {
    if (url.contains("password=")) {
      return url.replaceAll("password=[^&]*", "password=***");
    }
    return url;
  }
}
