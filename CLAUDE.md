# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Layout

The git root contains a single Maven project in `url-shortener/` (plus a stray root `.idea/`). Run all commands below from `url-shortener/`.

Stack: Java 21, Spring Boot 4.1.1, PostgreSQL, Redis, JWT (jjwt), MapStruct + Lombok. Base package `com.auradev.url_shortener`. Comments and Javadoc are written in Vietnamese; match that when editing existing files.

## Commands

Use the Maven wrapper (`mvnw.cmd` on Windows, `./mvnw` elsewhere).

```bash
# Start local infra (Postgres, Redis, Kafka, Kafka UI on :8090) - the app itself runs from the IDE / mvnw
docker compose -f docker-compose.dev.yml up -d

./mvnw spring-boot:run                 # runs with the `dev` profile (default in application.yaml)
./mvnw clean package -DskipTests       # build the jar
./mvnw test                            # all tests
./mvnw test -Dtest=ClassName#method    # single test

# Full prod stack (app built from Dockerfile + infra); needs a real .env.prod
docker compose -f docker-compose.prod.yml --env-file .env.prod up -d --build
```

The only test is `UrlShortenerApplicationTests.contextLoads`, a `@SpringBootTest` that needs Postgres and Redis reachable (start the dev compose first).

## Architecture

Layered Spring MVC REST API under `/api/v1` (`auth`, `users`, `admin`; path constants in `AppConstant`): `controller` -> `service` (interface) / `service/impl` -> `repository` (Spring Data JPA). DTOs live in `dto/request` and `dto/response`; entity <-> DTO conversion goes through MapStruct mappers (`mapper/`, component model `spring` set in `pom.xml`).

Annotation-processor order in `pom.xml` matters: Lombok, then `lombok-mapstruct-binding`, then MapStruct. Keep that order when touching the compiler plugin.

### Auth flow (spans several files)

Stateless JWT, no sessions, no `UserDetailsService`. See `SecurityConfig`, `JwtAuthenticationFilter`, `AuthServiceImpl`, `JwtService`, `TokenRedisService`.

- Each login creates a random `sessionId`; both access and refresh tokens carry it (claim `sid`). Multiple concurrent sessions per user are supported.
- Refresh tokens are stored in Redis as `RT:{userId}:{sessionId}`. Refresh compares the presented token to the stored one exactly.
- Logout blacklists the access token by `jti` (`BL:{jti}`, TTL = remaining lifetime) and deletes that session's `RT:` key. There is currently no "logout all devices".
- `JwtAuthenticationFilter` builds authentication purely from JWT claims (userId, username, roles) and never hits the DB, so role or status changes only take effect when the access token expires. It never throws: an invalid token just leaves the request unauthenticated, and Spring Security returns 401.
- `AppConstant.PUBLIC_ENDPOINTS` is the permit-all list; `/api/v1/admin/**` requires `ROLE_ADMIN`; everything else needs authentication. Method security (`@PreAuthorize`) is enabled.

### Errors and i18n

All errors go through `AppException(ErrorCode, args...)`. `ErrorCode` holds the code (`ERR_xxx` ranges documented in the enum), message key, and HTTP status. `GlobalExceptionHandler` and the 401/403 handlers in `SecurityConfig` both render the same `ApiResponse` shape, with messages resolved by `TranslatorUtils` from `src/main/resources/i18n/message.properties` and `message_vi.properties`. A new error needs an `ErrorCode` entry and a key in both property files.

### Config

`application.yaml` selects the `dev` profile; `application-dev.yaml` / `application-prod.yaml` hold the rest. Custom settings are bound by `JwtProperties` and `AdminProperties` (`app.jwt.*`, `app.admin.*`). `DataInitializer` creates the admin account from `app.admin.*` at startup. Dev uses `ddl-auto: update` (no migration tool).

## Gotchas

- `.env.dev` and `.env.prod` are intentionally tracked (public repo, placeholder values only). Real secrets are injected at deploy time, so never put real secrets in these files.
- `Dockerfile` healthcheck and `PUBLIC_ENDPOINTS` reference `/actuator/health`, but `spring-boot-starter-actuator` is not in `pom.xml`, so that endpoint does not exist and the container healthcheck will fail.
- `spring-kafka` and `spring-boot-starter-websocket` are declared and Kafka is configured, but no code uses them yet. Only the `User` entity exists, with no URL-shortening domain model so far.
- The Javadoc in `TokenRedisService` still describes an older single-session design (`RT:{userId}`) and a `REVOKE:` key; the code uses per-session keys and has no revoke-all.
- The dev profile has default JWT secret and admin credentials in `application-dev.yaml`; they must be overridden via env vars in prod.
