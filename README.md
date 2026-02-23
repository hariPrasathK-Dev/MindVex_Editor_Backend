# MindVex Backend

Spring Boot backend for the MindVex Intelligent Codebase Analyser platform.

## Tech Stack

- **Spring Boot 3.2.x** — Java 17
- **PostgreSQL** — Primary database (Flyway-managed schema, V1–V14 migrations)
- **Flyway** — Database migrations
- **JWT + GitHub OAuth2** — Authentication and GitHub integration
- **JGit** — Git repository cloning and history mining
- **SCIP** — Language-agnostic code intelligence (hover, references)
- **Springdoc/Swagger** — API docs at `/swagger-ui.html`
- **Maven** — Build tool

## Quick Start

### Prerequisites

- Java 17+
- Maven 3.6+
- PostgreSQL 15+ (or Docker)

### Setup

```bash
# 1. Copy the example env file and fill in values
copy .env.example .env

# 2. Start a local PostgreSQL instance (optional — via Docker)
docker-compose up -d

# 3. Run the application
mvn spring-boot:run
```

API: **http://localhost:8080**
Swagger UI: **http://localhost:8080/swagger-ui.html**

## Environment Variables

| Variable | Description |
|---|---|
| `DATABASE_URL` | JDBC URL, e.g. `jdbc:postgresql://localhost:5432/mindvex_db` |
| `JWT_SECRET` | Minimum 64-character random secret |
| `GITHUB_CLIENT_ID` | GitHub OAuth2 App client ID |
| `GITHUB_CLIENT_SECRET` | GitHub OAuth2 App client secret |
| `CORS_ORIGINS` | Comma-separated allowed origins, e.g. `http://localhost:5173` |
| `APP_OAUTH2_AUTHORIZED_REDIRECT_URIS` | Comma-separated OAuth2 redirect URIs |
| `GIT_REPO_BASE_DIR` | Directory where cloned repos are stored (default: `/tmp/mindvex-repos`) |
| `SPRING_PROFILES_ACTIVE` | `dev` (local) or `prod` (production) |

## API Endpoints

### Authentication

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/auth/register` | Register with email/password |
| `POST` | `/api/auth/login` | Login, returns JWT |
| `GET` | `/api/auth/oauth2/authorize/github` | Start GitHub OAuth2 flow |

### Users

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/users/me` | Get current user profile |
| `GET` | `/api/users/me/github-connection` | Get GitHub OAuth connection status |
| `DELETE` | `/api/users/me/github-connection` | Disconnect GitHub OAuth |

### Repository History

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/repository-history` | Save repository to history |
| `GET` | `/api/repository-history` | Get user's repository history |
| `DELETE` | `/api/repository-history/{id}` | Remove a single entry |
| `DELETE` | `/api/repository-history` | Clear all history |

### Code Graph

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/graph/build` | Clone a repo and build its dependency graph |
| `GET` | `/api/graph/dependencies` | Get file-level dependency graph |
| `GET` | `/api/graph/references` | Find all references to a symbol |

### SCIP Code Intelligence

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/scip/upload` | Upload a SCIP index file for processing |
| `GET` | `/api/scip/hover` | Get hover information for a symbol |
| `GET` | `/api/scip/jobs/{id}` | Check the status of an indexing job |

### Analytics

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/analytics/mine` | Clone a repo and mine its git history |
| `GET` | `/api/analytics/hotspots` | Get most-changed files (hotspot analysis) |
| `GET` | `/api/analytics/file-trend` | Get change frequency trend for a specific file |
| `GET` | `/api/analytics/blame` | Get evolutionary blame for a file |

## Project Structure

```
src/main/java/ai/mindvex/backend/
 config/          # CORS and Security config
 controller/      # REST controllers
 dto/             # Request/response DTOs
 entity/          # JPA entities
 exception/       # Global exception handler
 repository/      # Spring Data JPA repositories
 security/        # JWT filter, OAuth2 handlers
 service/         # Business logic
src/main/resources/
 application.yml           # Base config
 application-dev.yml       # Dev profile (verbose logging)
 application-prod.yml      # Prod profile (tighter pool, quiet logs)
 db/migration/             # Flyway SQL migrations (V1–V14)
```

## Profiles

- **dev** — verbose SQL/logging, reads all secrets from `.env`
- **prod** — minimal logging, reads all secrets from OS environment (Render/Railway/etc.)

## Troubleshooting

### Port in use

```powershell
netstat -ano | findstr :8080
taskkill /PID <PID> /F
```

### Database connection failed

```bash
docker-compose restart postgres
```

### Flyway migration failed

```bash
mvn flyway:clean flyway:migrate
```

## License

MIT
