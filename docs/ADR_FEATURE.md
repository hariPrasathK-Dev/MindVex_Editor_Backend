# Architecture Decision Records (ADR) Enhancement

## Overview

The Living Wiki module now includes comprehensive **Architecture Decision Records (ADR)** functionality that fetches real architectural context from GitHub and generates professional, industry-standard ADRs.

## What Are ADRs?

Architecture Decision Records document the **"why"** behind technical decisions in a codebase. They capture:

- **Context & Rationale**: Why was this decision made?
- **Alternatives Considered**: What other options were evaluated?
- **Consequences**: What trade-offs were accepted?
- **Technical Debt**: What maintenance overhead was incurred?
- **Non-Functional Requirements**: How does this impact security, performance, scalability?

## Key Benefits

### 1. **Onboarding & Knowledge Retention**
- New team members understand system evolution without asking original architects
- Reduces "why did we build it this way?" questions
- Prevents reinventing the wheel on already-solved problems

### 2. **Capturing Trade-offs**
- Documents pros and cons of each decision
- Helps teams anticipate future maintenance challenges
- Makes technical debt visible and trackable

### 3. **Asynchronous Communication**
- Provides written record of decisions
- Reduces time spent in meetings explaining past choices
- Enables distributed teams to stay synchronized

### 4. **Managing Change**
- Immutable historical record
- Can be superseded by new ADRs when requirements change
- Creates audit trail for compliance

## Implementation

### New Components

#### 1. **GitHubApiService** (`GitHubApiService.java`)

Fetches architectural decision context from GitHub:

```java
public class GitHubApiService {
    // Fetches commits with architecture-related keywords
    private List<CommitInfo> fetchArchitecturalCommits(...)
    
    // Fetches PRs discussing design decisions
    private List<PullRequestInfo> fetchArchitecturalPRs(...)
    
    // Fetches issues about architecture
    private List<IssueInfo> fetchArchitecturalIssues(...)
}
```

**Architecture Keywords Detected:**
- architecture, design, refactor, breaking change, migration
- ADR, decision, pattern, restructure, technical debt
- performance, scalability, security, framework, library
- database, cache, API, microservice, monolith

**Data Fetched:**
- **Commits**: Last 100 commits, filtered to 20 most relevant
- **Pull Requests**: Last 50 PRs (open + closed), filtered to 15 most relevant
- **Issues**: Search API with architecture keywords, up to 15 results

#### 2. **Enhanced LivingWikiService**

Integrates GitHub data into wiki generation:

```java
// Fetch user's GitHub token
String githubToken = getUserGithubToken(userId);

// Get architecture context from GitHub
ArchitectureContext archContext = 
    githubApiService.fetchArchitectureContext(repoUrl, githubToken);

// Format and include in AI prompt
context.append("─── GitHub Architecture Decision Records ───\n");
context.append("## Architectural Commits\n");
context.append("## Architectural Pull Requests\n");
context.append("## Architectural Issues & Discussions\n");
```

### ADR Format Standard

Each ADR follows this comprehensive format:

```markdown
### ADR-001: [Decision Title]

**Status:** Accepted | Proposed | Deprecated | Superseded

**Context:**
- What situation/problem required a decision?
- What constraints existed (technical, business, time)?
- When was this made? (From GitHub commit/PR dates)
- Who proposed it? (From GitHub authors)

**Decision:**
- What was decided? (Be specific and concrete)
- What alternatives were considered?
- Why was this chosen over alternatives?

**Consequences:**

**Positive:**
- Benefits gained
- Non-functional requirements addressed (security, performance, scalability)

**Negative:**
- Trade-offs accepted
- Technical debt incurred
- Maintenance overhead

**Risks:**
- Potential future issues
- Migration challenges

**References:**
- [GitHub Commit #abc123](https://github.com/owner/repo/commit/abc123)
- [PR #42: Migrate to microservices](https://github.com/owner/repo/pull/42)
- [Issue #15: Architecture discussion](https://github.com/owner/repo/issues/15)
```

## When to Create ADRs

The AI is instructed to create ADRs for these scenarios:

### 1. **Multiple Options Considered**
- Framework choice (React vs Vue, Spring Boot vs Express)
- Library selection
- Design pattern adoption

### 2. **Impacts Future Development**
- Breaking changes to APIs
- Migration between technologies
- Deprecation of features

### 3. **Affects Non-Functional Requirements**
- **Security**: OAuth2 vs JWT authentication
- **Performance**: Adding Redis cache layer
- **Scalability**: Migrating to microservices

### 4. **Affects Multiple Teams/Systems**
- API contract changes
- Shared infrastructure decisions
- Cross-cutting concerns

### 5. **Common Decision Types**
- Database schema changes (MySQL → PostgreSQL)
- Architecture patterns (monolith → microservices, REST → GraphQL)
- Security implementations (session-based → token-based auth)
- Performance optimizations (synchronous → asynchronous processing)
- Breaking API changes (versioning strategy)

## GitHub API Integration

### Data Sources

#### **1. Architectural Commits**
```
GET /repos/:owner/:repo/commits?per_page=100
```

Filters commits with messages containing architecture keywords:
- "refactor: Migrate authentication to OAuth2"
- "breaking: Change API response format"
- "feat: Add Redis cache layer"

**Example Output:**
```markdown
## Architectural Commits
Recent commits with architectural significance:

- **Migrate to microservices architecture** (2024-03-01)
  Author: john.doe | URL: https://github.com/owner/repo/commit/abc123
  
- **Add Redis cache layer for performance** (2024-02-15)
  Author: jane.smith | URL: https://github.com/owner/repo/commit/def456
```

#### **2. Architectural Pull Requests**
```
GET /repos/:owner/:repo/pulls?state=all&per_page=50&sort=updated
```

Filters PRs with architecture-related content in title/body/labels:

**Example Output:**
```markdown
## Architectural Pull Requests
Pull requests discussing design decisions:

- **PR #42: Migrate to PostgreSQL from MySQL** [merged]
  Labels: architecture, breaking-change, database
  Description: This PR migrates our database from MySQL to PostgreSQL...
  URL: https://github.com/owner/repo/pull/42
```

#### **3. Architectural Issues**
```
GET /search/issues?q=repo:owner/repo is:issue (architecture OR design OR refactor)
```

Uses GitHub Search API to find issues discussing architecture:

**Example Output:**
```markdown
## Architectural Issues & Discussions
Issues discussing architecture and design:

- **Issue #15: Should we adopt microservices?** [closed]
  Labels: architecture, discussion, decision
  Description: Team discussion about breaking monolith into microservices...
  URL: https://github.com/owner/repo/issues/15
```

### Authentication

Uses the user's GitHub access token stored in the database:

```java
private String getUserGithubToken(Long userId) {
    return userRepository.findById(userId)
            .map(User::getGithubAccessToken)
            .orElse(null);
}
```

**Token Permissions Required:**
- `repo` (for private repositories)
- `public_repo` (for public repositories)

If no token is available, GitHub context is skipped and ADRs are inferred from code structure only.

## AI Prompt Enhancement

### Before Enhancement
```
2. adr.md — Formal Architecture Decision Records.
   Document at least 5 key decisions with status, context,
   decision, and consequences. Base decisions on actual code structure.
```

### After Enhancement
```
2. adr.md — Architecture Decision Records (ADRs)

**CRITICAL: Use the GitHub Architecture Decision Records section below
(if provided) to create factual ADRs based on actual commits, pull requests,
and issues.**

Document at least 5-10 key architectural decisions using this format for EACH:

### ADR-###: [Decision Title]

**Status:** Accepted | Proposed | Deprecated | Superseded

**Context:**
- What is the situation/problem requiring a decision?
- What constraints exist (technical, business, time)?
- When was this decision made? (Use GitHub commit/PR dates if available)
- Who proposed it? (Use GitHub authors if available)

[... comprehensive format with Consequences, References, etc.]

**When to create an ADR:**
1. Multiple technical options were considered
2. Decision impacts future development
3. Decision affects non-functional requirements
4. Decision affects multiple teams or systems
[...]

**Example ADR Topics to Look For:**
- Framework choice (e.g., "Why React over Vue")
- Database decisions (e.g., "Migration from MySQL to PostgreSQL")
- Architecture patterns (e.g., "Adopting microservices")
[...]

IMPORTANT: Base ADRs on actual evidence from:
- GitHub commits with keywords like "refactor", "breaking change", "migration"
- GitHub PRs discussing design decisions
- GitHub issues about architectural choices
- Code structure patterns visible in the repository
```

## Anti-Hallucination Measures

To ensure factual, evidence-based ADRs:

### 1. **GitHub Data Priority**
```
CRITICAL: Use the GitHub Architecture Decision Records section below
(if provided) to create factual ADRs based on actual commits, PRs, issues.
```

### 2. **Explicit Evidence Requirement**
```
Base ADRs on actual evidence from:
- GitHub commits with keywords: refactor, breaking change, migration
- GitHub PRs discussing design decisions
- GitHub issues about architectural choices
- Code structure patterns visible in the repository
```

### 3. **Fallback Behavior**
```
If no GitHub data is available, infer decisions from code structure only.
DO NOT hallucinate decisions.
```

### 4. **"To be documented" Placeholder**
```
If information is missing, state "To be documented" rather than inventing.
```

## Usage Example

### User Flow

1. **User clones repository** via Living Wiki module
2. **Backend fetches GitHub data**:
   - User's GitHub token from database
   - Architectural commits (last 100, filtered to 20)
   - Architectural PRs (last 50, filtered to 15)
   - Architectural issues (search API, up to 15)
3. **Context is formatted** and included in AI prompt
4. **AI generates ADRs** based on real GitHub evidence
5. **User views ADRs** in Living Wiki interface

### Generated ADR Example

Based on real GitHub data:

```markdown
# Architecture Decision Records

## ADR-001: Migration from MySQL to PostgreSQL

**Status:** Accepted

**Context:**
- Needed better JSON support and full-text search capabilities
- MySQL's JSON features were limited
- Team had concerns about future scalability
- Decision made on February 15, 2024
- Proposed by jane.smith in PR #42

**Decision:**
Migrate database from MySQL 8.0 to PostgreSQL 15.

**Alternatives Considered:**
1. Stay with MySQL and use separate search service (Elasticsearch)
2. Migrate to MongoDB for JSON-first approach
3. Use PostgreSQL with native JSON support

**Why PostgreSQL?**
- Native JSONB support with indexing
- Built-in full-text search
- Better performance with complex queries
- Lower operational cost than separate search service

**Consequences:**

**Positive:**
- ✅ Native JSON support improved API response times by 40%
- ✅ Full-text search eliminated need for Elasticsearch
- ✅ Reduced monthly cloud costs by $500
- ✅ Team already familiar with PostgreSQL

**Negative:**
- ❌ 2-week migration downtime risk
- ❌ Required rewriting 15 stored procedures
- ❌ Some MySQL-specific features not available
- ❌ Increased PostgreSQL expertise needed

**Risks:**
- Data migration complexity for 2TB dataset
- Potential performance issues during cutover
- Team training required for PostgreSQL-specific features

**References:**
- [PR #42: Database Migration](https://github.com/mindvex/project/pull/42)
- [Issue #15: MySQL limitations discussion](https://github.com/mindvex/project/issues/15)
- [Commit abc123: PostgreSQL migration](https://github.com/mindvex/project/commit/abc123)

---

## ADR-002: Adopting Microservices Architecture

**Status:** Accepted

**Context:**
- Monolithic application became difficult to deploy
- Teams blocked on each other's changes
- Decision made on January 10, 2024
- Proposed by john.doe in Issue #20

**Decision:**
Break monolith into 5 microservices: Auth, Users, Products, Orders, Analytics.

[... rest of ADR ...]
```

## Testing the Feature

### 1. **Backend Verification**
```bash
# Check GitHubApiService exists
ls src/main/java/ai/mindvex/backend/service/GitHubApiService.java

# Check LivingWikiService has GitHub integration
grep "githubApiService" src/main/java/ai/mindvex/backend/service/LivingWikiService.java
```

### 2. **Run Backend**
```bash
mvn clean spring-boot:run
```

### 3. **Generate Living Wiki**
- Open Living Wiki module in frontend
- Select a repository (GitHub)
- Click "Generate Wiki"
- Wait for AI generation (2-5 minutes)
- Open `adr.md` file

### 4. **Verify GitHub Data**
Check backend logs for:
```
[GitHubAPI] Fetching architecture context for owner/repo
[GitHubAPI] Found X commits, Y PRs, Z issues with architectural context
[LivingWiki] Added GitHub architecture context (X commits, Y PRs, Z issues)
```

### 5. **Verify ADR Quality**
Look for:
- ✅ Real GitHub commit/PR/issue references
- ✅ Actual dates and authors
- ✅ Specific technical details
- ✅ Consequences section with trade-offs
- ✅ No hallucinated decisions

## API Rate Limits

GitHub API has rate limits:
- **Authenticated**: 5,000 requests/hour
- **Unauthenticated**: 60 requests/hour

Our implementation:
- **3 API calls per wiki generation**:
  1. `GET /repos/:owner/:repo/commits` (1 call)
  2. `GET /repos/:owner/:repo/pulls` (1 call)
  3. `GET /search/issues` (1 call)

**Total**: 3 requests per Living Wiki generation

**Max wikis per hour with token**: ~1,666 wikis
**Max wikis per hour without token**: ~20 wikis

## Troubleshooting

### Issue: No GitHub data in ADRs

**Possible Causes:**
1. User has no GitHub token configured
2. Repository URL is invalid
3. Repository is private and token lacks permissions
4. GitHub API rate limit exceeded

**Solutions:**
1. Check user has GitHub token: `SELECT github_access_token FROM users WHERE id = ?`
2. Verify repository URL format: `https://github.com/owner/repo`
3. Ensure token has `repo` or `public_repo` scope
4. Check GitHub rate limit: `GET /rate_limit`

### Issue: ADRs are generic/hallucinated

**Possible Causes:**
1. Repository has no architecture-related commits/PRs/issues
2. GitHub API failed but didn't raise error
3. AI ignored GitHub context in prompt

**Solutions:**
1. Check backend logs for "Added GitHub architecture context"
2. Verify GitHub data was actually fetched (check logs for commit/PR/issue counts)
3. Review AI prompt to ensure GitHub section is included

### Issue: GitHubApiService compilation errors

**Possible Causes:**
1. Missing dependencies in `pom.xml`
2. RestTemplate not configured

**Solutions:**
1. Ensure Spring Web dependency exists:
   ```xml
   <dependency>
       <groupId>org.springframework.boot</groupId>
       <artifactId>spring-boot-starter-web</artifactId>
   </dependency>
   ```
2. RestTemplate is instantiated in service: `private final RestTemplate restTemplate = new RestTemplate();`

## Future Enhancements

### Potential Improvements

1. **ADR Versioning**
   - Track ADR superseding (ADR-005 supersedes ADR-002)
   - Show ADR history and evolution

2. **ADR Categories**
   - Group by type (Security, Performance, Database, etc.)
   - Filter by status (Accepted, Deprecated, etc.)

3. **GitHub Discussions Integration**
   - Fetch from GitHub Discussions API
   - Include team consensus from discussions

4. **Code Analysis ADRs**
   - Detect architecture patterns from code
   - Infer decisions from framework usage

5. **ADR Templates**
   - Customizable ADR format
   - Team-specific templates

6. **ADR Metrics**
   - Track decision impact over time
   - Visualize technical debt accumulation

7. **ADR Export**
   - Generate PDF/HTML reports
   - Export for compliance audits

## References

### ADR Methodology
- [Michael Nygard's ADR Documentation](https://cognitect.com/blog/2011/11/15/documenting-architecture-decisions)
- [ADR GitHub Organization](https://adr.github.io/)
- [ThoughtWorks Technology Radar - ADRs](https://www.thoughtworks.com/radar/techniques/lightweight-architecture-decision-records)

### Implementation Details
- Stack Overflow: [Architecture Decision Records Best Practices](https://stackoverflow.com/questions/tagged/architecture-decision-records)
- GitHub API Documentation: [Search API](https://docs.github.com/en/rest/search)
- GitHub API Documentation: [Commits API](https://docs.github.com/en/rest/commits)

## Summary

The Living Wiki ADR enhancement brings **professional, evidence-based architecture documentation** to MindVex. By integrating with GitHub's API, we:

✅ **Document real decisions** from actual commits, PRs, and issues  
✅ **Capture context** including dates, authors, and discussions  
✅ **Track consequences** with detailed trade-off analysis  
✅ **Link to evidence** with GitHub URLs  
✅ **Follow industry standards** (Michael Nygard's ADR format)  
✅ **Prevent hallucination** by requiring actual GitHub data  
✅ **Support onboarding** with "why" documentation  
✅ **Enable async communication** with written records  
✅ **Manage change** with supersedable, versioned ADRs  

This makes MindVex a comprehensive codebase intelligence platform that not only analyzes code structure but also **preserves the reasoning behind architectural decisions**.
