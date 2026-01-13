# MindVex Backend API

Spring Boot backend for the MindVex AI Development Platform.

## Features

- ✅ JWT Authentication
- ✅ User Management (Registration, Login, Profile)
- ✅ Workspace CRUD Operations
- ✅ Chat History Storage
- ✅ PostgreSQL Database with Flyway Migrations
- ✅ Redis Caching
- ✅ CORS Configuration for Cloudflare Frontend
- ✅ Swagger/OpenAPI Documentation

## Tech Stack

- **Spring Boot 3.2.1** - Framework
- **Java 17** - Language
- **PostgreSQL 15** - Database
- **Redis 7** - Caching
- **Maven** - Build Tool
- **Flyway** - Database Migrations
- **JWT** - Authentication
- **Lombok** - Boilerplate Reduction

## Prerequisites

- Java 17 or higher
- Maven 3.6+
- Docker & Docker Compose (for database)
- PostgreSQL 15+ (if not using Docker)
- Redis 7+ (if not using Docker)

## Quick Start

### 1. Clone and Navigate

```bash
cd c:\Users\hp859\Desktop\IntelligentCodebaseAnalyser\MindVex_Editor_Backend
```

### 2. Start Database Services

```bash
docker-compose up -d
```

This starts PostgreSQL on port 5432 and Redis on port 6379.

### 3. Configure Environment

```bash
copy .env.example .env
```

Edit `.env` if needed (defaults work for local development).

### 4. Build the Project

```bash
mvn clean install
```

### 5. Run the Application

```bash
mvn spring-boot:run
```

The API will be available at: **http://localhost:8080**

### 6. Access Swagger UI

Open your browser: **http://localhost:8080/swagger-ui.html**

## API Endpoints

### Authentication

- `POST /api/auth/register` - Register new user
- `POST /api/auth/login` - Login user
- `POST /api/auth/refresh` - Refresh JWT token

### Users

- `GET /api/users/me` - Get current user profile
- `PUT /api/users/me` - Update current user profile

### Workspaces

- `POST /api/workspaces` - Create workspace
- `GET /api/workspaces` - Get all user workspaces
- `GET /api/workspaces/{id}` - Get workspace by ID
- `PUT /api/workspaces/{id}` - Update workspace
- `DELETE /api/workspaces/{id}` - Delete workspace

### Chats

- `POST /api/workspaces/{workspaceId}/chats` - Create chat
- `GET /api/workspaces/{workspaceId}/chats` - Get workspace chats
- `GET /api/chats/{id}` - Get chat by ID
- `POST /api/chats/{id}/messages` - Add message to chat
- `GET /api/chats/{id}/messages` - Get chat messages
- `DELETE /api/chats/{id}` - Delete chat

## Testing the API

### Register a User

```bash
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d "{\"email\":\"test@example.com\",\"password\":\"Test123!\",\"fullName\":\"Test User\"}"
```

### Login

```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d "{\"email\":\"test@example.com\",\"password\":\"Test123!\"}"
```

Save the `token` from the response.

### Create Workspace (Authenticated)

```bash
curl -X POST http://localhost:8080/api/workspaces \
  -H "Authorization: Bearer YOUR_TOKEN_HERE" \
  -H "Content-Type: application/json" \
  -d "{\"name\":\"My Workspace\",\"description\":\"Test workspace\"}"
```

## Database Migrations

Flyway automatically runs migrations on startup. Migration files are in:
```
src/main/resources/db/migration/
```

To manually run migrations:
```bash
mvn flyway:migrate
```

## Configuration

### Application Profiles

- **dev** - Development (verbose logging, local database)
- **prod** - Production (environment variables, minimal logging)

Set profile:
```bash
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `DATABASE_URL` | PostgreSQL JDBC URL | `jdbc:postgresql://localhost:5432/mindvex` |
| `DATABASE_USERNAME` | Database username | `mindvex` |
| `DATABASE_PASSWORD` | Database password | `mindvex_password` |
| `REDIS_HOST` | Redis host | `localhost` |
| `REDIS_PORT` | Redis port | `6379` |
| `JWT_SECRET` | JWT signing secret | (see .env.example) |
| `SPRING_PROFILES_ACTIVE` | Active profile | `dev` |

## Development

### Project Structure

```
src/main/java/ai/mindvex/backend/
├── config/              # Configuration classes
├── controller/          # REST controllers
├── dto/                 # Data Transfer Objects
├── entity/              # JPA entities
├── exception/           # Custom exceptions
├── repository/          # JPA repositories
├── security/            # Security & JWT
└── service/             # Business logic
```

### Adding New Endpoints

1. Create DTO in `dto/`
2. Add method to Service
3. Add endpoint to Controller
4. Update Swagger annotations

## Troubleshooting

### Port Already in Use

```bash
# Windows
netstat -ano | findstr :8080
taskkill /PID <PID> /F

# Linux/Mac
lsof -ti:8080 | xargs kill -9
```

### Database Connection Failed

```bash
# Check if PostgreSQL is running
docker ps | findstr postgres

# Restart database
docker-compose restart postgres
```

### Flyway Migration Failed

```bash
# Clean and rebuild
mvn flyway:clean flyway:migrate
```

## Next Steps

1. ✅ Complete remaining service implementations
2. ✅ Add comprehensive unit tests
3. ✅ Add integration tests
4. ✅ Implement file upload for workspaces
5. ✅ Add WebSocket support for real-time chat
6. ✅ Implement rate limiting
7. ✅ Add monitoring and metrics

## License

MIT

## Support

For issues, please create a GitHub issue or contact the development team.