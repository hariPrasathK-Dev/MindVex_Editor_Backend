# MindVex Backend

Spring Boot backend for the MindVex Intelligent Codebase Analyser platform.

## Tech Stack

- **Spring Boot 3.2.x** — Java 17
- **PostgreSQL** — Primary database (Flyway-managed schema)
- **Flyway** — Database migrations
- **JWT + GitHub OAuth2** — Authentication
- **Springdoc/Swagger** — API documentation available at `/swagger-ui.html`
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
| `POST` | `/api/auth/refresh` | Refresh access token |
| `GET` | `/api/auth/oauth2/authorize/github` | Start GitHub OAuth2 flow |

### Users
| Method | Path | Description |
|---|---|---|
| `GET` | `/api/users/me` | Get current user profile |
| `PUT` | `/api/users/me` | Update profile |

### Repository History
| Method | Path | Description |
|---|---|---|
| `POST` | `/api/repository-history` | Save repository to history |
| `GET` | `/api/repository-history` | Get user's repository history |
| `DELETE` | `/api/repository-history/{id}` | Remove from history |

### Code Graph & SCIP
| Method | Path | Description |
|---|---|---|
| `POST` | `/api/scip/index` | Trigger SCIP indexing of a repo |
| `GET` | `/api/scip/hover` | Hover info for a symbol |
| `GET` | `/api/scip/references` | Find all references to a symbol |
| `GET` | `/api/graph/dependencies` | Get file dependency graph |

### Analytics
| Method | Path | Description |
|---|---|---|
| `GET` | `/api/analytics/hotspots` | Most-changed files (hotspot analysis) |
| `GET` | `/api/analytics/churn` | Weekly code churn stats |
| `GET` | `/api/analytics/blame` | Git blame for a file |
| `GET` | `/api/analytics/diff` | Commit file diffs |

## Project Structure

```
src/main/java/ai/mindvex/backend/
├── config/          # CORS and Security config
├── controller/      # REST controllers
├── dto/             # Request/response DTOs
├── entity/          # JPA entities
├── exception/       # Global exception handler
├── repository/      # Spring Data JPA repositories
├── security/        # JWT filter, OAuth2 handlers
└── service/         # Business logic
src/main/resources/
├── application.yml           # Base config
├── application-dev.yml       # Dev profile (verbose logging)
├── application-prod.yml      # Prod profile (tighter pool, quiet logs)
└── db/migration/             # Flyway SQL migrations (V1–V14)
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