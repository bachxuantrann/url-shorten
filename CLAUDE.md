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

### URL domain (Phase 1)

`Url` entity (table `urls`, extends `BaseEntity`) is managed by `UrlController` -> `UrlService`/`UrlServiceImpl` under `/api/v1/urls` (authenticated; ownership via `userId`). Links of other users or with status `DELETED` are always reported as `URL_NOT_FOUND` (404). `BLOCKED` links cannot be modified/disabled/deleted by their owner. Status rules live in `UrlServiceImpl` (see its Javadoc); `EXPIRED` is only revived by an update with a new expiry.

Short codes: `ShortCodeGenerator` turns the next value of the Postgres sequence `short_code_seq` (`UrlRepository.nextShortCodeSequence`) into a 7-char Base62 code via a keyed Feistel permutation with cycle walking, so codes are unique by construction (bijection) and not guessable/countable. The key is `app.shortener.code-secret` (env `SHORT_CODE_SECRET`) and **must stay constant across deploys**. `app.shortener.base-url` (env `APP_BASE_URL`) builds `shortUrl` in responses. URL content checks (http/https, host present, no userinfo, max 2048) are in `UrlUtils`; target safety rules (SSRF, loops, blocklist) are in `UrlTargetPolicy` (see Abuse protection). List sorting is whitelisted in `UrlController` (`createdAt`, `clickCount`, `expiresAt`). `GET /api/v1/urls?keyword=` searches the owner's links by partial, case-insensitive match on `originalUrl` or `shortCode` (max 100 chars; `%`, `_` and `!` are escaped by `UrlServiceImpl.toLikePattern` and treated literally; combinable with `status` and paging).

### Redirect path (Phase 2)

Public `GET /{code}` (`RedirectController` -> `RedirectServiceImpl`) answers `302` + `Location` + `Cache-Control: no-store` (302 and no-store so link changes apply immediately and every click can be counted later). `SecurityConfig.redirectRequestMatcher()` permits any single-segment GET/HEAD path made of code characters that is not in `ShortCodes` reserved words (`/api`, `/actuator`, `/admin`... stay authenticated); the 3-32 length rule is enforced in the service so bad codes get a JSON 404 instead of 401. Add new top-level routes to `ShortCodes.RESERVED`.

Status mapping: unknown / malformed / reserved / `DISABLED` -> 404 (`URL_NOT_FOUND`), expired (`EXPIRED`, or `ACTIVE` past `expiresAt`) / `DELETED` -> 410 (`URL_GONE`), `BLOCKED` -> 403 (`URL_ACCESS_BLOCKED`).

Lookup is Redis cache-aside then Postgres (`UrlCacheService`, key `URL:{code}` = `STATUS|expiresAt|protected|originalUrl` or `MISSING` for negative caching (see Link extras for the `protected` field); TTL `app.shortener.cache-ttl` capped at time-to-expiry, `negative-cache-ttl` for misses). Redis errors are swallowed and treated as a cache miss, so a Redis outage never breaks redirects. **Any code path that changes a link's status, target or expiry (including future admin block, expiry job, alias changes) must call `UrlCacheService.evictAfterCommit(code)`**; tests that change a row directly through the repository must call `evict`. A tiny race (reader loads DB just before a commit, writes cache just after the eviction) can leave a stale entry for at most `cache-ttl`.

### Link lifecycle (Phase 3)

Expiry is enforced **lazily at redirect time** (`RedirectServiceImpl` compares `expiresAt` with the clock), so correctness never depends on a job. `UrlExpirationJob` (`@Scheduled`, every `app.shortener.expiry-job-interval`, default `PT1M`, declared in `application-dev/prod.yaml` along with the cache TTLs and batch size; disable with `app.shortener.expiry-job-enabled=false`) only syncs state by moving `ACTIVE` links past `expiresAt` to `EXPIRED` through `UrlExpirationService.expireBatch` (one transaction per batch of `app.shortener.expiry-batch-size`, default 500). A batch is **one SQL statement** (`UrlRepository.expireDueLinks`: `WITH due AS (... FOR UPDATE SKIP LOCKED) UPDATE ... RETURNING short_code`), so statements do not grow with the number of links (a regression test counts them; an entity-by-entity version issued N+1). Multi-instance safety comes from `SKIP LOCKED` (each instance claims different rows), not from a distributed lock. Because it is a bulk update, JPA auditing does not run and the query sets `updated_at`/`updated_by` (`system`) itself; cache keys are evicted with a single Redis `DEL` via `UrlCacheService.evictAllAfterCommit`. Only `ACTIVE` links are touched: `DISABLED`/`BLOCKED`/`DELETED` keep their status.

`UrlServiceImpl.toResponse` reports an `ACTIVE` link past its `expiresAt` as `EXPIRED` so the API matches redirect (410) before the job runs; the `status=` list filter still filters on the stored status. Deleted links are soft-deleted (`status=DELETED`, `deletedAt`) and their code is never reused.

Time: inject the `Clock` bean (`AppConfig`) and use `LocalDateTime.now(clock)` for anything involving link expiry or cache TTL, so tests can use `Clock.fixed`. Surefire sets `app.shortener.expiry-job-interval=PT24H` so the scheduler never fires during the suite; `UrlExpirationSchedulingTest` overrides it to `PT1S` to prove the scheduling wiring.

### Abuse protection (Phase 4)

- **Anonymous links:** `POST /api/v1/public/urls` (`PublicUrlController`, public) creates a link with `userId = null` (`createdBy = anonymous`). Nobody owns it, so only an admin can block it. A valid/invalid token on that endpoint is ignored.
- **Rate limiting:** `RateLimitService` (Redis fixed window, one atomic Lua script `INCR`+`EXPIRE`, key `RL:{bucket}:{subject}`), rules in `RateLimitProperties` (`app.rate-limit.anonymous-create` per client IP, default 10/h; `user-create` per user id, default 100/h; `limit <= 0` disables). Exceeding throws `RateLimitExceededException` -> HTTP 429 + `Retry-After` (`GlobalExceptionHandler`). Every request counts, including ones rejected for a bad URL. It **fails open** when Redis is down. The client IP is `request.getRemoteAddr()` (`ClientIpUtils`); `server.forward-headers-strategy: native` lets Tomcat swap in `X-Forwarded-For` **only when the peer is a trusted proxy** (private/loopback ranges), so internet clients cannot spoof it; never read that header manually. Integration tests rely on this by sending `X-Forwarded-For` from loopback to get independent counters.
- **Per-user quota:** `app.shortener.max-links-per-user` (default 1000, `<= 0` = unlimited) counts non-deleted links with one `COUNT` (not locked, so concurrent creates can overshoot by a few) -> `URL_QUOTA_EXCEEDED` (403).
- **Target policy** (`UrlTargetPolicy`, run on create **and** update, after `UrlUtils.validate`): (1) own host (host of `base-url` + `app.shortener.own-hosts`) -> `UNSAFE_URL_TARGET` (redirect loops); (2) unless `block-private-targets=false`, localhost, private/loopback/link-local (cloud metadata) / reserved IPv4 in any notation, private IPv6, `.local`/`.internal`/... names and single-label hosts -> `UNSAFE_URL_TARGET`; (3) `blocked_domains` (host or any parent domain, one query) -> `BLOCKED_DOMAIN`. `HostUtils` is purely syntactic and **never resolves DNS**, so a public name that resolves to a private IP is not detected; that is acceptable only while the server never fetches target URLs itself. If a feature starts fetching URLs server-side (link preview, malware scan) it must re-check the resolved IP.
- **Admin moderation** (`ROLE_ADMIN`, `/api/v1/admin/**`): `GET/PUT /urls/{code}[/block|/unblock]` (block needs a reason, stored in `urls.blocked_reason`; unblock sets `ACTIVE`; the owner sees `status=BLOCKED` + reason but cannot modify it) and `POST/GET/DELETE /blocked-domains` (`POST` can also block every existing `ACTIVE/DISABLED/EXPIRED` link of that domain with one `UPDATE ... RETURNING` using `HostUtils.urlHostRegex`; removing a domain does **not** unblock links already blocked). Block/unblock and bulk blocking evict the redirect cache after commit.
- Admin ops use the actor's username for `updated_by` (entity updates via auditing; the bulk statement via `AuditorAwareImpl`).

### Link extras (Phase 5)

- **Custom alias:** `CreateUrlRequest.alias` (3-32 chars `[0-9a-zA-Z_-]`, case-sensitive, immutable) becomes `urls.short_code`. Only for logged-in users: the anonymous endpoint uses `CreatePublicUrlRequest` (URL + expiry only) and `createAnonymous` ignores alias/password. Reserved words, taken aliases and aliases of deleted links (codes are never reused) all answer **409 `ERR_407`** with one message; malformed ones are 400 validation errors. `urls.short_code` was widened to 32 chars in V6 to match `ShortCodes.MAX_LENGTH`.
- **Alias vs generated code collision:** a 7-char alias can equal a code the generator produces later. A failed `INSERT` (unique violation `uq_urls_short_code`) aborts the Postgres transaction, so `UrlServiceImpl.createLink` runs each attempt in its **own** `TransactionTemplate` transaction and retries with a fresh code (max 5) from outside; the alias path never retries (it answers 409). Integration-tested on real Postgres. Do not turn `create` back into a single `@Transactional` method. BCrypt hashing also happens outside the transaction.
- **Password-protected links:** `urls.password_hash` (BCrypt via the shared `PasswordEncoder`, input 4-64 chars and at most 72 UTF-8 bytes), set at creation or via `PUT /api/v1/urls/{code}/password`, removed via `DELETE` on the same path (separate endpoints on purpose: `PUT /{code}` replaces target+expiry and must not silently drop protection). Responses only expose `passwordProtected`. `GET /{code}` of a protected link answers **403 `ERR_408`** (status/expiry rules are checked first, so a disabled/expired/blocked link never reveals it has a password). The client (future frontend page) calls **`POST /api/v1/public/urls/{code}/unlock`** `{password}` and receives `{originalUrl}` to navigate to (wrong password 403 `ERR_409`). `unlockTarget` always reads the DB and checks status/expiry before spending CPU on BCrypt.
- **The cache never holds a protected link's target or hash:** value is `STATUS|expiresAt|protected|originalUrl` (`protected` = `P` or `-`, URL empty when `P`); old 3-field entries are treated as misses. Setting/removing a password evicts the cache like any other change.
- **Unlock brute-force limits** (`RateLimitProperties`, checked in `PublicUrlController` in this order): per IP 30/15 min, per (link, IP) 10/15 min, per link 100/h; exceeding gives 429 + `Retry-After`. The code format is validated before any limiter key is created so invented codes cannot create unbounded Redis keys. Known trade-off: exhausting the per-link budget (needs many IPs) delays legitimate unlocks of that link for up to an hour.
- **QR codes:** `GET /api/v1/urls/{code}/qr?size=` (owner only, 404 otherwise) returns a PNG of `{base-url}/{code}` generated on the fly by `QrCodeGenerator` (ZXing core, error correction M, quiet zone 4, size clamped to 128-1024, default 320, `Cache-Control: private, max-age=3600`). Nothing is stored. Tests decode the PNG with ZXing (`support/QrDecoder`) to prove it scans.

### Errors and i18n

All errors go through `AppException(ErrorCode, args...)`. `ErrorCode` holds the code (`ERR_xxx` ranges documented in the enum), message key, and HTTP status. `GlobalExceptionHandler` and the 401/403 handlers in `SecurityConfig` both render the same `ApiResponse` shape, with messages resolved by `TranslatorUtils` from `src/main/resources/i18n/message.properties` and `message_vi.properties`. A new error needs an `ErrorCode` entry and a key in both property files.

### Config

`application.yaml` selects the `dev` profile; `application-dev.yaml` / `application-prod.yaml` hold the rest. Custom settings are bound by `JwtProperties` and `AdminProperties` (`app.jwt.*`, `app.admin.*`). `DataInitializer` creates the admin account from `app.admin.*` at startup. Schema is managed by **Flyway** (`src/main/resources/db/migration`, `V{n}__name.sql`); Hibernate runs with `ddl-auto: validate` in every profile, so every entity change needs a new migration. Never edit an applied migration. Flyway settings live per profile, not in `application.yaml`: dev sets `baseline-on-migrate: true` (adopts a legacy DB as V1, can be dropped once every dev DB has run Flyway); prod sets it `false` so it refuses a non-empty DB with no Flyway history, plus `clean-disabled: true`. Entities that need auditing extend `BaseEntity` (`createdAt`/`updatedAt` via Hibernate, `createdBy`/`updatedBy` via Spring Data auditing and `AuditorAwareImpl`: username, `anonymous` for unauthenticated requests, `system` outside a request). It uses Lombok `@SuperBuilder`, so subclasses must too, and a table for such an entity needs `created_at`, `updated_at`, `created_by`, `updated_by` NOT NULL columns. Bulk `@Modifying`/native update queries bypass auditing, so they must set the audit columns themselves (see `UrlRepository.expireDueLinks`).

## Coding rules

- **Never write code that causes N+1 queries.** The number of SQL statements for an operation must not grow with the number of rows it handles. This covers both N+1 *selects* (loading a collection or association once per row, e.g. a lazy/EAGER association touched inside a loop over query results) and N *writes* (updating or inserting row by row where one statement would do). Check this on every query, loop over entities, mapper that touches associations, and list/paging endpoint you add or change.
- Ways to stay clear of it: `JOIN FETCH` / `@EntityGraph` for associations that are needed, a single `WHERE id IN (...)` query instead of a query per id, projections or DTO queries when you do not need whole entities, one bulk statement (`UPDATE ... WHERE ...`, `INSERT ... SELECT`, `UPDATE ... RETURNING`) instead of mutating entities in a loop, and Redis multi-key commands (`DEL`/`MGET` with a collection) instead of one call per key. If you do need JPA batching for inserts/updates, enable it explicitly (`hibernate.jdbc.batch_size`, `order_inserts`/`order_updates`) and prove it with a test.
- `User.roles` is an `EAGER` element collection. Any query returning several users used to load roles one user at a time (an N+1 found in the admin user list: 3 users = 5 statements, 40 users = 42). It now carries `@BatchSize(size = 100)` (= `AppConstant.MAX_PAGE_SIZE`), so roles of a whole page load in one `IN (...)` query; do not replace this with `JOIN FETCH` on a paged query (Hibernate would paginate in memory). Guarded by `UserRolesQueryCountIntegrationTest`. Any new `@ElementCollection`/`@OneToMany` that is loaded alongside a list of parents needs the same treatment.
- Bulk statements bypass JPA auditing, so they must set `updated_at`/`updated_by` themselves (see `UrlRepository.expireDueLinks`).
- Prove it with a test, do not just reason about it: enable Hibernate statistics at runtime (`entityManagerFactory.unwrap(SessionFactory.class).getStatistics()`, `setStatisticsEnabled(true)`, `clear()`) and assert `getPrepareStatementCount()` stays constant for N = a few vs a few dozen rows. See `UrlExpirationIntegrationTest.expiringManyLinksIssuesAConstantNumberOfStatements`.

## Gotchas

- `.env.dev` and `.env.prod` are intentionally tracked (public repo, placeholder values only). Real secrets are injected at deploy time, so never put real secrets in these files.
- `spring-kafka` and `spring-boot-starter-websocket` are declared and Kafka is configured, but no code uses them yet. No click tracking exists yet (Phase 6).
- The dev profile has default JWT secret and admin credentials in `application-dev.yaml`; they must be overridden via env vars in prod.

## Working agreements and open items

Handoff notes so a new session can continue without the previous conversation.

### How the owner wants to work

- **Reply in Vietnamese.** Code comments and Javadoc are Vietnamese too (this file is English).
- **Config goes in the profile files** (`application-dev.yaml` / `application-prod.yaml`), not in `application.yaml` (Flyway flags, shortener/rate-limit settings, `server.forward-headers-strategy`). The base file only holds truly shared things (app name, i18n, Jackson, server port).
- **After each phase, report what was done**: what changed and why, tests added and the total, deviations from this plan, caveats, and what is **not** verified (for example Bruno requests or the owner's running IDE app, which you cannot exercise). Say plainly when something failed or was skipped.
- **Verify yourself**: run `./mvnw test` (a few minutes, containers start first) and prove claims with tests instead of reasoning, e.g. count SQL statements with Hibernate statistics for N+1 questions. Baseline: **351 tests green at the end of Phase 5** (the link-password tests are slow because of BCrypt cost 12).
- **Do not commit or push** unless asked; the owner reviews the diff and commits between phases. Do not run destructive commands against the dev stack (`docker compose down -v`, dropping tables) without asking: the dev Postgres is the owner's.
- **The owner edits files too** (their Bruno app rewrites request files and pastes tokens; they replaced the dev `code-secret` default). Treat on-disk changes as deliberate and never revert them.
- Environment notes: repo files may be CRLF, so scripts that edit them must preserve line endings; very long Bash commands with many heredocs fail to parse, so write files with the file tools; Python on Windows cannot open paths over 260 characters, so use the short scratchpad path for scripts. Maven's incremental compile can keep **stale test classes** after you change a main constructor or signature and then report BUILD SUCCESS: use `./mvnw clean test-compile` after such changes.

### Bruno collection (outside the repo)

`C:\Users\ADMIN\Documents\bruno\base project`, in Bruno's OpenCollection YAML format: `opencollection.yml`, one folder per area (`authentication`, `users`, `admin`, `url`) each with `folder.yml`, and one `.yml` per request (`info` / `http` / `runtime` / `settings`). Assertions and variables live in `runtime.scripts` entries of `type: after-response` (`test(...)`, `bru.setVar(...)`). Requests use runtime variables `{{accessToken}}`, `{{adminToken}}`, `{{shortCode}}`; the owner supplies tokens by logging in. When you add an endpoint, add a request to the matching folder with tests. Dev admin account: `admin` with the default from `application-dev.yaml`.

### Known gaps (not yet fixed)

- **Malformed JSON request body probably returns 500** instead of 400, and unknown routes for authenticated users probably return 500 too: `GlobalExceptionHandler` has no handler for `HttpMessageNotReadableException` / `NoResourceFoundException`, so they fall into the generic `Exception` handler. Believed from reading the code, not tested.
- There is **no admin API to list or search all links** (an admin can only fetch one by short code).
- `urls.click_count` stays 0 until click tracking (Phase 6).
- `AppConstant.PUBLIC_ENDPOINTS` lists `/swagger-ui/**` and `/v3/api-docs/**` but springdoc is not a dependency, so those paths return 404.
- `UserController` still has its own `getAuthenticatedUserId` helper (newer code uses `SecurityUtils`), and `UrlApiIntegrationTest` has private HTTP helpers that duplicate `support/ApiClient`.
- `UrlService.createAnonymous` takes the full `CreateUrlRequest` and ignores its alias/password; a dedicated command type would be cleaner.
- Prod env: `.env.prod` still has `CHANGE_ME_*` placeholders. `APP_BASE_URL` and `SHORT_CODE_SECRET` must be real values before deploying, and `SHORT_CODE_SECRET` must never change afterwards.

### Phase 6 design brief (click tracking via Kafka)

**Start of the next session:** run `git status` (the owner commits between phases), then `./mvnw clean test` and expect **351 green** before touching anything. Then **ask the owner the open decisions below before writing code**, and build in small verified steps (migration + partition job, producer, consumer, tests) rather than one big change.

**Verified starting state (commit `b043aa7`):**

- `spring-kafka` is a dependency but **no Kafka code exists in `src/main`**. Kafka config lives only in `application-dev/prod.yaml`: String key/value (de)serializers, `acks: all`, `retries: 3`, consumer `group-id: url-shortener-group`, `auto-offset-reset: earliest`. Dev and prod compose run one KRaft broker with `KAFKA_AUTO_CREATE_TOPICS_ENABLE=true` and replication factor 1, so a topic created implicitly would get a single partition: **declare topics explicitly with `NewTopic` beans** (partitions, replicas) instead.
- `TestcontainersConfiguration` already starts Kafka (`ConfluentKafkaContainer`, bootstrap servers wired by a `DynamicPropertyRegistrar`) but nothing uses it yet.
- The app's `ObjectMapper` bean is **Jackson 2** (`com.fasterxml`, `JavaTimeModule`, `JacksonConfig`). Because the existing config uses String serializers, serialize `ClickEvent` to a JSON string with that mapper instead of Spring Kafka's JSON serializers (Boot 4 may expect Jackson 3 classes there; not verified).
- Hook points: `RedirectController.redirect` (after `resolveTarget` succeeds) and `PublicUrlController.unlock` (after `unlockTarget` succeeds). The client IP comes from `ClientIpUtils.resolve`. The redirect cache does not carry the link's database id.

**Design decisions already made (see also the plan below):** emit only on a **successful** redirect or unlock (never on 403/404/410, including `ERR_408`); Kafka message key = `shortCode` (per-link ordering); every event has a unique `eventId` and a timestamp **set when the event is created**; consumer is idempotent and batched; failures must never break a redirect.

**Pitfalls to design around (found by thinking this through, not yet tested):**

1. **A dead broker can block redirects.** `KafkaTemplate.send` blocks up to `max.block.ms` (60 s by default) while metadata is unavailable. Set a small `max.block.ms` and `delivery.timeout.ms` for this producer, send asynchronously, count/log failures in the callback, and test with the broker stopped (use a dedicated container or a producer pointed at a closed port, not the shared one).
2. **Idempotency vs partitioning.** On a partitioned table every unique constraint must include the partition key, so `event_id UNIQUE` becomes `UNIQUE (event_id, clicked_at)` and the primary key `(id, clicked_at)`. Redelivery only hits the same unique key if `clicked_at` is the **event's** timestamp, never the consumer's `now()`.
3. **Count only rows actually inserted.** Use `INSERT ... ON CONFLICT DO NOTHING RETURNING url_id` and derive `urls.click_count` / `url_daily_stats` deltas from the returned rows, never from the batch size, or redelivered events are counted twice. Aggregate per link and apply one bulk `UPDATE` / upsert per batch (no N+1; set audit columns yourself; keep the statement-count test rule).
4. **Resolving `url_id`:** have the event carry `shortCode` and let the consumer map codes to ids with one `SELECT id, short_code FROM urls WHERE short_code IN (...)` per batch (links deleted in the meantime still resolve: rows are soft-deleted). This keeps the cache format unchanged.
5. **Monthly partitions:** `url_clicks` is partitioned by `clicked_at`; Flyway creates the first partitions and a scheduled job (single-runner safe like `UrlExpirationJob`) creates upcoming months; consider a DEFAULT partition so a missed month never drops data. A foreign key to the non-partitioned `urls` table is allowed.
6. **Poison messages:** use a `DefaultErrorHandler` with bounded back-off and a `DeadLetterPublishingRecoverer` to `url-clicks.DLT`; a malformed event must not block the partition.
7. Keep surefire's `expiry-job-interval=PT24H` approach for any new scheduled job, and make Kafka listeners deterministic in tests (wait for the consumer with Awaitility, unique `shortCode`s per test).

**Open decisions to ask the owner first (give a recommendation with each):**

1. **IP privacy.** Recommended: never store the raw IP; store a keyed hash (`HMAC-SHA256` with a server secret and a per-day salt over IP + User-Agent) as the visitor identifier for unique-visitor counts, and keep only coarse data (country, device, browser, OS). Alternatives: truncate to /24 (IPv4) or /48 (IPv6), or store the raw IP.
2. **Country lookup.** Recommended: MaxMind GeoLite2 (needs a free account, a license key and an `.mmdb` file mounted into the container; decide where the file lives and how it is refreshed). Alternatives: take the country from a CDN header such as `CF-IPCountry` when behind a CDN, or skip country until it is needed.
3. **User-Agent parsing library** (for device/browser/OS), evaluated for size and maintenance.
4. **Retention:** how long to keep raw `url_clicks` partitions (for example 13 months) before a job drops old partitions; aggregates in `url_daily_stats` are kept.

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
1. **Core URL domain (DONE):** `urls` table + `UrlStatus`, short-code generator, authenticated CRUD under `/api/v1/urls` (create/get/list/update/disable/soft-delete), ownership checks (404 for other users' links), new `ErrorCode`s + i18n keys, concurrency test for code generation.
2. **Redirect path (DONE):** public `GET /{code}` (add to `PUBLIC_ENDPOINTS`, reserved-code list), status-to-HTTP mapping, Redis cache + negative cache, TTL = min(default, time to expiry), cache invalidation on update/disable/delete/block, Redis-down fallback.
3. **Lifecycle (DONE):** lazy expiry check on redirect, scheduled job ACTIVE -> EXPIRED (single-runner lock when multi-instance), injectable `Clock` for tests.
4. **Anonymous links and abuse protection (DONE):** `/api/v1/public/urls`, Redis rate limiting (429 + `Retry-After`), strict URL validation (http/https only, block localhost/private IPs/SSRF and self-redirect loops), per-user quota, domain blocklist, `BLOCKED` status, admin block/unblock.
5. **Link extras (DONE):** custom alias (409 on conflict), password-protected links (`password_hash`, attempt limit), QR code generated on the fly (not stored).
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
