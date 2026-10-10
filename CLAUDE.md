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

Integration tests use Testcontainers (Postgres, Redis, Kafka via `src/test/.../support/TestcontainersConfiguration`, add `@Import` to the test class), so they need a running Docker daemon but **not** the dev compose, and never touch the dev database. The first run pulls images and is slow; Spring caches the context, so keep test classes on the same configuration to share one set of containers.

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

`application.yaml` selects the `dev` profile; `application-dev.yaml` / `application-prod.yaml` hold the rest. Custom settings are bound by `JwtProperties` and `AdminProperties` (`app.jwt.*`, `app.admin.*`). `DataInitializer` creates the admin account from `app.admin.*` at startup. Schema is managed by **Flyway** (`src/main/resources/db/migration`, `V{n}__name.sql`); Hibernate runs with `ddl-auto: validate` in every profile, so every entity change needs a new migration. Never edit an applied migration. Flyway settings live per profile, not in `application.yaml`: dev sets `baseline-on-migrate: true` (adopts a legacy DB as V1, can be dropped once every dev DB has run Flyway); prod sets it `false` so it refuses a non-empty DB with no Flyway history, plus `clean-disabled: true`. Entities that need auditing extend `BaseEntity` (`createdAt`/`updatedAt` via Hibernate, `createdBy`/`updatedBy` via Spring Data auditing and `AuditorAwareImpl`: username, `anonymous` for unauthenticated requests, `system` outside a request). It uses Lombok `@SuperBuilder`, so subclasses must too, and a table for such an entity needs `created_at`, `updated_at`, `created_by`, `updated_by` NOT NULL columns. Bulk `@Modifying` queries bypass auditing.

## Gotchas

- `.env.dev` and `.env.prod` are intentionally tracked (public repo, placeholder values only). Real secrets are injected at deploy time, so never put real secrets in these files.
- `spring-kafka` and `spring-boot-starter-websocket` are declared and Kafka is configured, but no code uses them yet. Only the `User` entity exists, with no URL-shortening domain model so far.
- The dev profile has default JWT secret and admin credentials in `application-dev.yaml`; they must be overridden via env vars in prod.

## Product direction and development plan

Goal: a TinyURL-style shortener. Currently only auth/user management exists (base project); the URL domain is built in the phases below. Build phase by phase; each phase must be runnable and tested on its own.

### Design decisions (already made)

- `users.id` stays **UUID** (used in JWT claims and Redis keys). `urls.user_id` is a UUID FK, nullable for anonymous links. `urls.id` is `BIGSERIAL`.
- `short_code` is the public identifier (UNIQUE), `id` is internal. Generated codes: DB sequence -> scramble -> Base62 (min 6-7 chars). Custom aliases share the same column, so reserved words (`api`, `swagger-ui`, `actuator`, ...) must be rejected.
- Status enum `UrlStatus`: `ACTIVE`, `DISABLED`, `EXPIRED`, `BLOCKED`, `DELETED`. `expires_at` is separate from `status`.
- Soft delete (`deleted_at`). A short code once issued is **never reused**.
- Redirect uses **302** (301 would be cached by browsers and lose click stats). Responses: expired -> 410, missing -> 404, blocked -> 403.
- Redirect path: Redis cache-aside (with negative caching) -> Postgres fallback if Redis is down. Click tracking is async via Kafka and must never break a redirect.
- Kafka is at-least-once: every `ClickEvent` carries a unique `eventId`; consumers are idempotent (`ON CONFLICT DO NOTHING`).
- Schema is managed by Flyway (replace `ddl-auto: update` with `validate` in Phase 0). `url_clicks` is partitioned by month. Per-country/device stat tables are only added when a dashboard needs them.

### Phases

0. **Foundation (DONE):** Flyway (V1 = existing `users`), Testcontainers (Postgres/Redis/Kafka), add actuator (Dockerfile healthcheck needs it), `BaseEntity` auditing, CI (build + test), fix stale `TokenRedisService` Javadoc.
1. **Core URL domain:** `urls` table + `UrlStatus`, short-code generator, authenticated CRUD under `/api/v1/urls` (create/get/list/update/disable/soft-delete), ownership checks (404 for other users' links), new `ErrorCode`s + i18n keys, concurrency test for code generation.
2. **Redirect path:** public `GET /{code}` (add to `PUBLIC_ENDPOINTS`, reserved-code list), status-to-HTTP mapping, Redis cache + negative cache, TTL = min(default, time to expiry), cache invalidation on update/disable/delete/block, Redis-down fallback.
3. **Lifecycle:** lazy expiry check on redirect, scheduled job ACTIVE -> EXPIRED (single-runner lock when multi-instance), injectable `Clock` for tests.
4. **Anonymous links and abuse protection:** `/api/v1/public/urls`, Redis rate limiting (429 + `Retry-After`), strict URL validation (http/https only, block localhost/private IPs/SSRF and self-redirect loops), per-user quota, domain blocklist, `BLOCKED` status, admin block/unblock.
5. **Link extras:** custom alias (409 on conflict), password-protected links (`password_hash`, attempt limit), QR code generated on the fly (not stored).
6. **Click tracking (Kafka):** `url_clicks` with unique `event_id`, topic `url-clicks` keyed by `shortCode`, async producer, idempotent batch consumer with retry + dead-letter topic, enrichment (User-Agent, GeoIP) in the consumer only, privacy handling for IPs, monthly partitions.
7. **Analytics:** `url_daily_stats` unique `(url_id, stat_date)` upserted by the consumer, batched `urls.click_count` updates, unique-visitor estimate, stats APIs (owner/admin only), reconciliation job comparing `click_count` vs `url_clicks`.
8. **Reliability and observability (logging/telemetry via OTLP + SigNoz):**
   - Export **logs, traces and metrics over OTLP** to **SigNoz** (OTLP gRPC `4317` / HTTP `4318`). Use the OpenTelemetry Java agent or the Micrometer-tracing OTLP bridge plus a Logback OTLP appender; endpoint and service name come from env vars (`OTEL_EXPORTER_OTLP_ENDPOINT`, `OTEL_SERVICE_NAME=url-shortener`), never hard-coded.
   - Run SigNoz self-hosted via its own compose file, separate from `docker-compose.dev.yml` (it brings ClickHouse and is heavy). Prod points at the SigNoz collector via env.
   - Keep correlation: trace/span IDs injected into log lines (MDC) so a slow redirect can be traced across Redis, Postgres and Kafka.
   - Custom metrics: redirect latency, cache hit ratio, Kafka send failures, consumer lag.
   - Resilience4j timeouts/circuit breakers for Redis and Kafka, k6 load test (target p99 redirect < 50 ms on cache hit), multi-instance run behind nginx, manual chaos tests (Redis/Kafka/consumer down).
9. **Frontend and deployment:** web UI, prod config hardening (CORS, HTTPS, real secrets, no `show-sql`), deploy pipeline, Postgres backups.
10. **Later:** API keys, custom domains, Safe Browsing check, email verification.

MVP = Phases 0-2 (create a link, click it, get redirected).
