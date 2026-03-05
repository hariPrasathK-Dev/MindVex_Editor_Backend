# MindVex Database & Graph Building Guide

## ✅ Current Status

**Database:**

- ✅ `vector_embeddings` table created in Railway
- ✅ All 18 tables exist and ready
- ✅ Backend deployed to Render (running on port 10000)

**Code:**

- ✅ Embedding generation fix (commit 0b1da00)
- ✅ Race condition fix (commit 7b77314)
- ✅ Both commits pushed to GitHub and deployed

---

## 🎯 How to Build the Graph (UI Steps)

### **Step 1: Login**

1. Go to https://mindvex-editor.pages.dev
2. Click **"Sign in with GitHub"**
3. Authorize the GitHub OAuth app

### **Step 2: Import Repository**

1. After login, you'll see the main dashboard
2. Click **"Import from GitHub"** button (top section)
3. Paste a repository URL (example: `https://github.com/your-username/your-repo`)
4. Click **"Import"** or press Enter
5. Wait a few seconds for import confirmation

### **Step 3: Build Graph** (Critical Step!)

1. Look for **"Quick Actions"** in the top navigation menu
2. Click on **"Quick Actions"**
3. You'll see the imported repository URL in the top-right input field
4. **Look for the REFRESH BUTTON (🔄)** next to the status indicator
5. **CLICK THE REFRESH BUTTON** - This triggers the graph_build job!

**What happens:**

- Status changes to 🟡 **"Building graph..."** (job created)
- Then 🔵 **"Waiting for dependencies..."** (waiting for backend processing)
- Finally 🟢 **"Graph Ready"** (complete!)

**OR alternatively:**  
You can click on any of these tools which will auto-trigger graph building if needed:

- **"Architecture / Dependency Graph"** ⭐ (Main graph builder)
- **"Knowledge Graph Construction"**
- **"Impact Analysis"**
- **"Living Wiki & Documentation"**

### **Step 4: Verify Embeddings**

**Windows:**

1. Double-click `quick-check.bat` in the backend folder
2. This will show you row counts for all tables
3. Check if `Vector Embeddings` has rows > 0

**PowerShell:**

```powershell
cd c:\Users\hp859\Desktop\IntelligentCodebaseAnalyser\MindVex_Editor_Backend
.\quick-check.bat
```

---

## 🔍 Render Logs - What to Check

Go to Render Dashboard → Your Backend Service → **Logs** tab

**Successful graph_build job logs:**

```log
[IndexJobWorker] Processing job id=XX type=graph_build repo=https://github.com/...
[IndexJobWorker] Starting graph_build for repo=...
[SourceCodeDepExtractor] Starting extraction for user=X repo=...
[SourceCodeDepExtractor] Found 108 source files in...
[SourceCodeDepExtractor] Saved 38 edges for...
[IndexJobWorker] Dependency extraction done: 38 edges              ← Always here

[EmbeddingIngestion] Generating embeddings for repo ...            ← MUST SEE THIS!
[EmbeddingIngestion] Ingested 150 chunks for repo ...              ← MUST SEE THIS!
[IndexJobWorker] Embedding generation done: 150 chunks             ← MUST SEE THIS!

Job id=XX type=graph_build completed successfully
```

**If you DON'T see the `[EmbeddingIngestion]` lines:**

- The fixes didn't deploy properly
- Or you're looking at old logs (filter by timestamp)

---

## 📊 Database Verification

**Check Tables Exist:**

```powershell
# Set DATABASE_URL environment variable
$env:DATABASE_URL = "jdbc:postgresql://crossover.proxy.rlwy.net:47666/railway?sslmode=disable&user=postgres&password=wao6iCL1WFwCKPhJ1Usfw0QzTtJnPsGN"

# Run verification
cd c:\Users\hp859\Desktop\IntelligentCodebaseAnalyser\MindVex_Editor_Backend
$cp = "target\classes;$env:USERPROFILE\.m2\repository\org\postgresql\postgresql\42.7.9\postgresql-42.7.9.jar"
& 'C:\Program Files\Java\jdk-21\bin\java.exe' -cp $cp ai.mindvex.backend.util.QuickCheck
```

**Expected Output:**

```
📊 ROW COUNTS:
   ✅ Users                           7 rows
   ✅ Index Jobs                     15 rows
   ✅ File Dependencies              38 rows
   ✅ Vector Embeddings             150 rows  ← MUST HAVE ROWS!
   ✅ Wiki Summaries                  5 rows
```

---

## 🎯 Quick Reference

| Action            | Location                                          |
| ----------------- | ------------------------------------------------- |
| Import Repository | Dashboard → "Import from GitHub" button           |
| Build Graph       | Quick Actions → Click refresh button (🔄)         |
| View Graph        | Quick Actions → "Architecture / Dependency Graph" |
| View Wiki         | Quick Actions → "Living Wiki & Documentation"     |
| Check Embeddings  | Run `quick-check.bat`                             |
| Render Logs       | Render Dashboard → Backend Service → Logs         |

---

## ✅ Success Criteria

- [ ] Repository imported successfully
- [ ] Clicked refresh in Quick Actions
- [ ] Status showed "Building..." → "Polling..." → "Ready"
- [ ] Render logs show `[EmbeddingIngestion] Ingested X chunks`
- [ ] Quick-check shows `Vector Embeddings: X rows` (X > 0)
- [ ] Can view Architecture Graph
- [ ] Can view Living Wiki with generated docs

---

## 🔧 Troubleshooting

**Problem: No embeddings generated**

- Check Render logs for `[EmbeddingIngestion]` messages
- Verify GEMINI_API_KEY is set in Render environment variables
- Check if graph_build job status is "failed" in database

**Problem: Graph build times out**

- Large repos take longer (2-5 minutes)
- Check Render logs for errors
- Try a smaller repository first

**Problem: Living Wiki shows empty**

- Embeddings must exist first (run graph_build)
- Check `wiki_summaries` table has rows
- Wait for wiki generation job to complete

---

## 📝 Files Created for You

- `quick-check.bat` - Quick database status checker
- `CreateVectorEmbeddingsTable.java` - One-time table creation (already used)
- `QueryDatabase.java` - Interactive SQL query tool
- `QuickCheck.java` - Simple verification utility
- `VerifyDatabase.java` - Comprehensive verification (has column mismatches)

**You can delete the util files after verification if you want - they're not needed for production.**

---

**Created by: GitHub Copilot**
**Date: March 6, 2026**
