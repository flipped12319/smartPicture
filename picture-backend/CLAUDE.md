# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

```bash
# Build the project
mvn clean package

# Run the application (starts on port 8123, context-path /api)
mvn spring-boot:run

# Run all tests
mvn test

# Run a single test class or method
mvn test -Dtest=ClassName
mvn test -Dtest=ClassName#methodName
```

## Tech Stack

- **Java 11**, **Spring Boot 2.7.6**
- **MySQL** via **MyBatis-Plus 3.5.15** — all queries use `QueryWrapper`, pagination via `PaginationInnerInterceptor`, logical delete via `@TableLogic` on `isDelete` column
- **Redis** (`StringRedisTemplate`) + **Caffeine** local cache — two-tier caching for picture list endpoints
- **Tencent COS** (`cos_api 5.6.227`) — object storage for uploaded pictures, with automatic webp compression and thumbnail generation
- **JWT** (`jjwt 0.11.5`) — stateless auth; users log in and receive a Bearer token
- **Knife4j 4.4.0** — API docs at `/api/doc.html`
- **Hutool 5.8.38**, **Lombok**, **Jsoup 1.15.3**

## Architecture Overview

### Request Lifecycle

```
Request → CORS filter → @AuthCheck (AOP) → Controller → Service → Mapper (MyBatis-Plus) → MySQL
                                                           ↓
                                                      CosManager → Tencent COS
                                                           ↓
                                                      Caffeine / Redis cache
```

### Unified Response Model

Every controller returns `BaseResponse<T>` built via `ResultUtils.success(data)` or `ResultUtils.error(code, msg)`. The response format is `{ code: 0, data: ..., message: "ok" }`. Exceptions thrown anywhere are caught by `GlobalExceptionHandler` and converted to this format.

### Authentication & Authorization

- **JWT stateless auth**: On login, `UserController` returns a JWT token in `LoginResponse`. Subsequent requests pass it as `Authorization: Bearer <token>`. `UserService.getLoginUserByToken(token)` resolves the user.
- **`@AuthCheck(mustRole = UserConstant.ADMIN_ROLE)`** on any controller method triggers `AuthInterceptor` (AOP `@Around`), which reads the logged-in user and checks the required role. If the annotation has no `mustRole`, the user just needs to be logged in.
- Roles: `user` and `admin` (see `UserRoleEnum`).

### Entity / VO / DTO Separation

- **Entity** (`model/entity/`) — database-mapped objects (e.g., `User`, `Picture`, `Space`)
- **VO** (`model/vo/`) — sanitized objects returned to clients (strips sensitive fields)
- **DTO** (`model/dto/`) — request bodies, organized into sub-packages: `picture/`, `space/`, `user/`, `file/`, `ai/`

### Picture Upload Flow (Template Method)

`PictureUploadTemplate` defines the upload pipeline: validate → generate filename → create temp file → process input source → upload to COS → build result → cleanup. Subclasses `FilePictureUpload` and `UrlPictureUpload` implement the abstract steps (`validPicture`, `getOriginFilename`, `processFile`). The template handles webp compression and thumbnail generation via COS image processing rules.

### Picture Review & Private Spaces

- Pictures start with `reviewStatus` of PENDING. Admins approve/reject via `POST /review`.
- Pictures belong to an optional **space** (via `spaceId`). Spaces have tiered levels (普通版/专业版/旗舰版) with different capacity limits (`SpaceLevelEnum`).
- **Private space** pictures bypass the caching layer and are queried directly; the requesting user must own the space.
- **Public space** pictures (no `spaceId`) are cached in a Caffeine→Redis→MySQL chain, with a 5-minute TTL. Regular users only see approved pictures.

### Cache Strategy

Picture list endpoints use a two-tier cache: Caffeine (in-process, fast) → Redis (distributed) → MySQL. Cache key is `MD5(query-params-json)`. Private-space queries skip the cache entirely.

### AI Chat

`AiController` proxies to an external Python agent service via `ChatAgentService.sendToAgent()`, which uses `RestTemplate` to call `agent.api.url` (configured in application.yml, default `http://127.0.0.1:8000/chat`).

## Key Conventions

- **Field mapping**: `mybatis-plus.map-underscore-to-camel-case: false` — entity fields and DB columns must match exactly. Use `@TableField(value = "columnName")` when they differ.
- **Logical delete**: `isDelete` column with `@TableLogic` — MyBatis-Plus automatically appends `isDelete = 0` to all queries.
- **Parameter validation**: Use `ThrowUtils.throwIf(condition, ErrorCode, message)` rather than throwing exceptions directly.
- **`application-local.yml`** is gitignored — it contains COS credentials and other secrets. Production config overrides go there.
