# IssueFlow – Implementation Guide

A working memory document for building the IssueFlow backend. Captures every endpoint, constraint, and non-obvious implementation note pulled from `TDP_issueflow_requirements.pdf` and the `README.md` API contract.

---

## 1. Tech Stack & Baseline

- **Language**: Java 21 (pinned in `pom.xml`)
- **Framework**: Spring Boot 3.4.2 (parent already set)
- **DB**: PostgreSQL 16 via Docker (`compose.yml`) – user/pass/db all `issueflow`, port `5432`
- **ORM**: Spring Data JPA (Hibernate), `ddl-auto: update` is currently on – switch to `validate` once schema is stable, or use Flyway/Liquibase for migrations
- **Validation**: `spring-boot-starter-validation` (Jakarta Bean Validation)
- **CSV**: Apache Commons CSV 1.10.0 (already in pom)
- **Test DB**: H2 (already in pom, test scope)
- **Multipart**: 10 MB cap already configured in `application.yaml`

### Dependencies likely to add
- `spring-boot-starter-security` – for JWT filter chain & password hashing
- `io.jsonwebtoken:jjwt-api/impl/jackson` (or `nimbus-jose-jwt`) – JWT signing
- `springdoc-openapi-starter-webmvc-ui` – Swagger UI (nice-to-have)
- `org.testcontainers:postgresql` – integration tests against real PG (optional but cleaner than H2 for some features)

### Suggested package layout
```
com.att.tdp.issueflow
├── auth          # JWT filter, login/logout/me, token denylist
├── user
├── project
├── ticket
├── comment
├── dependency
├── attachment
├── audit
├── mention
├── workload      # workload + auto-assignment service
├── escalation    # @Scheduled job
├── csv           # export/import
├── common        # ApiError, GlobalExceptionHandler, BaseEntity, enums
└── config        # SecurityConfig, JwtConfig, etc.
```

Each domain bundle = `Controller`, `Service`, `Repository`, `Entity`, `dto/`.

---

## 2. Domain Model & Enums

### Enums
- `Role`: `ADMIN`, `DEVELOPER`
- `TicketStatus`: `TODO`, `IN_PROGRESS`, `IN_REVIEW`, `DONE` (forward-only ordering)
- `Priority`: `LOW`, `MEDIUM`, `HIGH`, `CRITICAL` (ordinal used for escalation step)
- `TicketType`: `BUG`, `FEATURE`, `TECHNICAL`
- `AuditAction`: `CREATE`, `UPDATE`, `DELETE`, `RESTORE`, `AUTO_ASSIGN`, `AUTO_ESCALATE`, `LOGIN`, `LOGOUT`, `COMMENT_CREATE`, `COMMENT_UPDATE`, `COMMENT_DELETE`, etc. – keep a single enum but cover all state changes.
- `AuditActor`: `USER`, `SYSTEM`
- `EntityType`: `USER`, `PROJECT`, `TICKET`, `COMMENT`, `DEPENDENCY`, `ATTACHMENT`

### Entities (high level)

| Entity | Key fields |
|---|---|
| `User` | id, username (unique), email (unique), fullName, role, passwordHash, createdAt |
| `Project` | id, name, description, ownerId (FK→User), deletedAt (nullable), createdAt, updatedAt |
| `Ticket` | id, title, description, status, priority, type, projectId, assigneeId (nullable), dueDate (nullable), isOverdue (default false), version (`@Version`), deletedAt, createdAt, updatedAt |
| `Comment` | id, ticketId, authorId, content, version (`@Version`), createdAt, updatedAt |
| `CommentMention` | id, commentId, mentionedUserId (unique composite (commentId, mentionedUserId)) |
| `TicketDependency` | id, ticketId, blockerTicketId (unique composite) |
| `Attachment` | id, ticketId, filename, contentType, sizeBytes, storagePath/bytes, uploadedBy, uploadedAt |
| `AuditLog` | id, action, entityType, entityId, performedBy (nullable for SYSTEM), actor, timestamp, payload/diff (jsonb optional) |
| `TokenDenylist` | id, jti, expiresAt – for logout |

### Important columns
- `Ticket.version` and `Comment.version` → `@Version` enables **JPA optimistic locking** for the "two users can't update simultaneously" requirement. On stale write → throw `OptimisticLockException` → map to `409 Conflict`.
- `Project.deletedAt`, `Ticket.deletedAt` → soft delete. Use Hibernate `@SQLRestriction("deleted_at IS NULL")` on the entity so standard reads transparently exclude soft-deleted rows. Native queries handle the `/deleted` endpoints explicitly.

---

## 3. Full Endpoint Map

> ⚠️ The README contract uses a few non-RESTful paths. Implement them **exactly as written** – the README is the contract. Notable oddities:
> - `POST /users/update/:userId` for user update (not PATCH)
> - `GET /tickets?projectId=...` is the list endpoint (no `/projects/:id/tickets`)
> - `DELETE` on tickets/projects is the **soft-delete** operation

### Auth
| Method | Path | Notes |
|---|---|---|
| POST | `/auth/login` | body `{username, password}` → `{accessToken, tokenType:"Bearer", expiresIn:3600}` |
| POST | `/auth/logout` | adds current token's `jti` to denylist |
| GET  | `/auth/me` | returns authenticated user profile |

### Users
| Method | Path | Notes |
|---|---|---|
| GET    | `/users` | list all |
| GET    | `/users/{userId}` | by id |
| POST   | `/users` | create – body `{username,email,fullName,role}` (password handling: see §4) |
| POST   | `/users/update/{userId}` | **POST not PATCH** – body `{fullName, role}` |
| DELETE | `/users/{userId}` | hard delete (spec says delete a user; only tickets/projects are soft) |
| GET    | `/users/{userId}/mentions?page=&pageSize=` | returns paginated `{data, total, page}` |

### Projects
| Method | Path | Notes |
|---|---|---|
| GET    | `/projects` | excludes soft-deleted |
| GET    | `/projects/{id}` | 404 if soft-deleted |
| POST   | `/projects` | body `{name, description, ownerId}` |
| PATCH  | `/projects/{id}` | body `{name?, description?}` |
| DELETE | `/projects/{id}` | soft delete |
| GET    | `/projects/deleted` | ADMIN only |
| POST   | `/projects/{id}/restore` | ADMIN only |
| GET    | `/projects/{projectId}/workload` | list `{userId, username, openTicketCount}` sorted asc |

### Tickets
| Method | Path | Notes |
|---|---|---|
| GET    | `/tickets?projectId={id}` | list (excludes soft-deleted) |
| GET    | `/tickets/{id}` | by id |
| POST   | `/tickets` | body has `dueDate` optional; `assigneeId` optional → triggers auto-assignment |
| PATCH  | `/tickets/{id}` | partial update with concurrency check; resets escalation if priority changed manually |
| DELETE | `/tickets/{id}` | soft delete |
| GET    | `/tickets/deleted?projectId={id}` | ADMIN only |
| POST   | `/tickets/{id}/restore` | ADMIN only |
| GET    | `/tickets/export?projectId={id}` | CSV download |
| POST   | `/tickets/import` | multipart `file` + form `projectId` |

### Comments
| Method | Path | Notes |
|---|---|---|
| GET    | `/tickets/{ticketId}/comments` | list |
| POST   | `/tickets/{ticketId}/comments` | body `{authorId, content}` – parse mentions |
| PATCH  | `/tickets/{ticketId}/comments/{commentId}` | optimistic-locked update, re-evaluate mentions |
| DELETE | `/tickets/{ticketId}/comments/{commentId}` | hard delete |

### Dependencies
| Method | Path | Notes |
|---|---|---|
| POST   | `/tickets/{ticketId}/dependencies` | body `{blockedBy: <id>}` |
| GET    | `/tickets/{ticketId}/dependencies` | list of blockers `[{id,title,status}]` |
| DELETE | `/tickets/{ticketId}/dependencies/{blockerId}` | remove |

### Attachments
| Method | Path | Notes |
|---|---|---|
| POST   | `/tickets/{ticketId}/attachments` | multipart `file`, ≤10MB, allow png/jpeg/pdf/plain only |
| DELETE | `/tickets/{ticketId}/attachments/{attachmentId}` | delete |

### Audit Log
| Method | Path | Notes |
|---|---|---|
| GET    | `/audit-logs?entityType=&entityId=&action=&actor=` | filterable read-only |

---

## 4. Authentication (JWT)

- Configure `SecurityFilterChain` with stateless sessions.
- Permit `POST /auth/login` and **only** that – everything else requires `Authorization: Bearer <jwt>`.
- **Password**: spec doesn't mention a register-with-password endpoint. Two ways to reconcile:
  1. Extend `POST /users` body to accept `password` (preferred). Store as BCrypt hash; never return.
  2. Seed a default password via `data.sql` for demo users (e.g., all users have password `password`).
  Pick (1) and document the addition in `prompts.md` / `run.md`.
- **JWT claims**: `sub=userId`, `username`, `role`, `iat`, `exp`, `jti` (UUID for denylist).
- **Logout**: persist `jti` + `exp` in `token_denylist`. JWT filter rejects denylisted jti. Run a cleanup job to purge expired entries.
- **`/auth/me`**: load user from `SecurityContextHolder.getContext().getAuthentication().getPrincipal()` → return profile.
- Inject signing key + expiry via `application.yaml` (`security.jwt.secret`, `security.jwt.ttl-seconds: 3600`).

---

## 5. Validation & Error Handling

- DTOs annotated with `@NotBlank`, `@Email`, `@Pattern`, `@Size`, `@NotNull`, `@Future` (for dueDate optional).
- Enum fields validated via `@Pattern` on string input OR by Jackson deserialization (let invalid enum → 400).
- One `@RestControllerAdvice` handling:
  - `MethodArgumentNotValidException` → 400 with field errors
  - `ConstraintViolationException` → 400
  - `EntityNotFoundException` / custom `NotFoundException` → 404
  - `IllegalStateException` (business rule violations) → 422 or 400 (pick one, document)
  - `OptimisticLockException` / `ObjectOptimisticLockingFailureException` → **409 Conflict** with message "Resource was modified by another user"
  - `AccessDeniedException` → 403
  - `AuthenticationException` → 401
  - `MaxUploadSizeExceededException` → 413
  - Fallback → 500 with generic message
- Standard error body:
  ```json
  { "timestamp": "...", "status": 400, "error": "Bad Request", "message": "...", "path": "...", "fieldErrors": [...] }
  ```

---

## 6. Business Rules – Critical Constraints

### Ticket lifecycle (must enforce on every PATCH)
1. **Cannot update once DONE**: if `existing.status == DONE` → `409` or `400`, "Ticket is closed."
2. **Forward-only status**: define `TicketStatus.ordinal()` ordering. `new.ordinal() < current.ordinal()` → reject. Equal is allowed.
3. **Blocked by unresolved deps**: cannot move to `DONE` if any `TicketDependency.blocker.status != DONE`. Check before save.
4. **Concurrency**: `@Version` on Ticket. Map optimistic-lock failures to 409. (Spec: "A ticket can't be updated simultaneously by two users.")
5. **Manual priority change** resets escalation: if `request.priority != current.priority` AND change came from a user (not the scheduler) → set `isOverdue = false`.

### Comment editing
- `@Version` on Comment → 409 on conflict (spec: "Two users can't edit a comment in the same time").

### Soft delete
- `Project` and `Ticket` only. `Comment`, `Dependency`, `Attachment`, `User` are hard deletes.
- Reads must transparently exclude soft-deleted (`@SQLRestriction`).
- Restoring a project does NOT auto-restore its tickets (document this choice; spec doesn't specify).
- Soft-delete-listing + restore endpoints are **ADMIN-only** – enforce with `@PreAuthorize("hasRole('ADMIN')")`.

### Mentions
- Regex: `@([A-Za-z0-9_.-]+)` (or whichever charset matches your username rules).
- **Case-insensitive** match against `username`. Store the matched `userId` (not the raw text).
- On comment update, diff old vs new mention sets; INSERT new, DELETE removed (idempotent via the unique composite key).
- Comment response always includes `mentionedUsers: [{id, username, fullName}]`.
- `GET /users/{id}/mentions` orders by `comment.createdAt DESC`, paginated.

### Auto-Assignment (on ticket CREATE only)
- Fired only when `assigneeId` is null/missing in the create body.
- Candidate pool: **users with role `DEVELOPER`**. (Spec: ADMINs excluded.)
- "Linked to the project" – interpretation: users who currently have at least one non-DONE ticket in the project, OR all DEVELOPER users system-wide. Spec is ambiguous. **Default to all DEVELOPERs system-wide** (simplest), but document the choice. If no DEVELOPER exists → `assigneeId = null`, no error.
- Workload = COUNT of tickets in this project where `assignee_id = user.id AND status != DONE AND deleted_at IS NULL`.
- Tie-break: oldest `User.createdAt` first.
- Record audit row: `actor=SYSTEM`, `action=AUTO_ASSIGN`, `entityType=TICKET`, `entityId=<new ticket id>`, `performedBy=null`.

### Auto-Escalation (background job)
- `@Scheduled(fixedDelay = ...)` – pick a sensible cadence (e.g., every minute) and configure via property.
- Query: tickets where `dueDate IS NOT NULL AND dueDate < now() AND status != DONE AND deletedAt IS NULL`.
- For each:
  - If `priority < CRITICAL` → step up one rung (`LOW→MEDIUM→HIGH→CRITICAL`).
  - If already `CRITICAL` AND still overdue → set `isOverdue = true`.
- Idempotent: a CRITICAL+overdue ticket should stop escalating.
- Audit row per change: `actor=SYSTEM`, `action=AUTO_ESCALATE`.
- Status field is **never** touched by escalation.

### Workload endpoint
- `GET /projects/{id}/workload` returns ALL users in the project (active assignees + project owner? – spec says "all users in the project"). Practical choice: **list every DEVELOPER plus the project owner**, with their `openTicketCount` (0 included), sorted ASC. Document the choice.

---

## 7. CSV Export / Import

### Export
- Endpoint: `GET /tickets/export?projectId={id}`
- Response headers: `Content-Type: text/csv`, `Content-Disposition: attachment; filename="tickets-<projectId>.csv"`.
- Columns (exact order from spec): `id,title,description,status,priority,type,assigneeId`
- Use `CSVPrinter` with `CSVFormat.DEFAULT.withQuoteMode(ALL_NON_NULL)` so commas/quotes/newlines in descriptions are escaped correctly.

### Import
- Endpoint: `POST /tickets/import` – multipart with `file` and form field `projectId`.
- Stream parse with `CSVParser` (`CSVFormat.DEFAULT.withFirstRecordAsHeader()`).
- For each row, validate (status/priority/type enums, title non-blank, etc.). Skip + collect errors – do not abort the whole batch.
- Response shape (exact): `{"created": <n>, "failed": <n>, "errors": [ { "row": <int>, "message": "..." } ]}`.
- Audit each created ticket. Decide: skip auto-assignment for imported rows when `assigneeId` is present, otherwise apply auto-assign rule. Document.

---

## 8. Attachments

- Configured multipart cap 10 MB matches spec. Spring will throw `MaxUploadSizeExceededException` automatically → handler returns 413.
- **Content-type check at controller boundary** (do not trust client). Use `file.getContentType()` and validate against `Set.of("image/png","image/jpeg","application/pdf","text/plain")`. Reject with 400.
- Storage decision (document the choice):
  - **Simple**: store bytes in a `BYTEA` column.
  - **Better**: store under a configurable filesystem dir; persist `storagePath` only.
  Pick simple unless asked otherwise.
- Persist `uploadedBy = currentUserId`. Audit `CREATE` / `DELETE`.

---

## 9. Audit Log

- Write audit rows from within the same `@Transactional` boundary as the state change → consistent on rollback.
- Cleanest approach: a Spring `@TransactionalEventListener` or a `AuditService.log(...)` invoked at the end of each service method.
- For SYSTEM actions (scheduler, auto-assign), `performedBy = null`, `actor = SYSTEM`.
- For USER actions, pull current user from `SecurityContextHolder`.
- Filtering: build a dynamic query with `Specification<AuditLog>` (JPA Criteria) using each non-null query param.

---

## 10. Concurrency Strategy Summary

| Resource | Mechanism |
|---|---|
| Ticket update | JPA `@Version` (optimistic) → 409 on conflict |
| Comment update | JPA `@Version` (optimistic) → 409 on conflict |
| Auto-escalation vs user PATCH | Both go through optimistic locking; scheduler should retry on conflict (use `@Retryable`) |
| Auto-assignment race | Done inside the create transaction; SELECT FOR UPDATE not needed because assignment is best-effort on creation |

Clients should send the resource version (e.g., `If-Match` header or `version` in body). For simplicity, accept `version` field in PATCH body and pass to JPA's merge. Without a client-supplied version you can still detect concurrent writes via the JPA-managed `@Version` row check, but only if the entity is loaded and modified within one transaction. **Document this: require `version` in PATCH bodies for tickets and comments.**

---

## 11. Configuration Additions

Extend `application.yaml`:
```yaml
security:
  jwt:
    secret: ${JWT_SECRET:change-me-in-production-min-32-chars-long}
    ttl-seconds: 3600

issueflow:
  escalation:
    fixed-delay-ms: 60000
  attachments:
    max-bytes: 10485760
    allowed-content-types: image/png,image/jpeg,application/pdf,text/plain
  auto-assign:
    enabled: true
```

`spring.jpa.hibernate.ddl-auto: update` is fine for the assignment; consider `validate` + Flyway scripts under `src/main/resources/db/migration/` if there's time.

Also: the skeleton has a stale `schema.sql` + `data.sql` referencing a `task` table. **Delete both** (or replace with valid bootstrap data) before first run — `spring.sql.init.mode: always` will execute them at every startup and crash if columns no longer match.

---

## 12. Testing Strategy

Spring Boot test docs: https://docs.spring.io/spring-boot/reference/testing/index.html

- **Unit tests** (Mockito) for services: business rules (lifecycle, escalation step, auto-assign tie-break).
- **`@DataJpaTest`** for repositories: soft-delete filtering, mention queries, workload aggregation.
- **`@SpringBootTest` + `MockMvc`** for controllers: HTTP status, JSON shape, validation errors.
- **Security tests**: protected endpoints reject anonymous, JWT issued by `/auth/login` is accepted, logout invalidates.
- **Concurrency tests**: two concurrent updates → second one 409 (use `TestTransaction` or two threads with `@Transactional(propagation=REQUIRES_NEW)`).
- **CSV tests**: round-trip export → import, plus malformed-row → goes into `errors` array.
- **Scheduler test**: bean injection with `@SpyBean`, manual invocation, asserts priority bump + audit row.
- H2 is in pom for tests; configure `src/test/resources/application.yaml` to use H2 in PG-compat mode, or switch to Testcontainers Postgres for higher fidelity (preferred if time allows).

---

## 13. Gotchas & Decisions to Document

1. **`POST /users/update/:userId`** is intentionally non-RESTful per contract — keep it.
2. **`Role` for user creation**: README sample doesn't include `password` — extending the body. Document in `run.md` and `prompts.md`.
3. **Login is `username + password`** — every demo user needs a password. Either seed all with `password` or have a separate `POST /auth/register` (spec doesn't ask for one — stick with `POST /users + password`).
4. **Auto-assignment candidate pool**: chose "all DEVELOPERs system-wide" — document.
5. **Workload listing membership**: chose "DEVELOPERs + project owner" — document.
6. **Restoring a project does NOT auto-restore tickets** — document.
7. **`isOverdue` reset on manual priority change** — applies only when priority field is actually present in PATCH body AND differs from current.
8. **Comment delete is hard delete** — not in soft-delete scope.
9. **Audit log includes `LOGIN`/`LOGOUT`** — debatable; do it for completeness.
10. **Stale `schema.sql`/`data.sql`** in the skeleton reference a `task` table — must remove/replace.

---

## 14. Build Order (suggested implementation sequence)

1. Common: `BaseEntity`, enums, error handler, `ApiError` DTO
2. User entity + repository + service + controller (no auth yet)
3. Security: JWT filter, `SecurityConfig`, `AuthController` (login/logout/me)
4. Project CRUD + soft delete + restore
5. Ticket CRUD + lifecycle rules + `@Version` concurrency
6. Comment CRUD + `@Version`
7. Mentions (parser + persistence + GET endpoint)
8. Dependencies CRUD + DONE-blocked-by check
9. Audit logging service, wire into all state changes
10. Auto-assignment service (on ticket create)
11. Workload endpoint
12. Auto-escalation scheduler
13. Attachments
14. CSV export/import
15. Tests across the board
16. `run.md`, `prompts.md`, README polish

---

## 15. Reference – Spec → File Map

- Requirements PDF sections 2.x → core CRUD endpoints + JWT
- 3.1 Audit Log → `audit/*`
- 3.2 Dependencies → `dependency/*`, ticket DONE-check
- 3.3 Attachments → `attachment/*`, multipart config
- 3.4 Export/Import → `csv/*`
- 3.5 Soft delete → `@SQLRestriction`, `/deleted` + `/restore` endpoints
- 3.6 Mentions → `mention/*`, regex parser, comment response shape
- 3.7 Escalation → `escalation/EscalationScheduler`, `Ticket.isOverdue`
- 3.8 Auto-assignment → `workload/AutoAssignService`, workload endpoint
- 4.1 Validation → DTOs + `@RestControllerAdvice`
- 4.3 Testing → `src/test/java/...`
- 4.4 Docs → `run.md`
- 4.5 AI artifacts → `prompts.md`, skill/instructions files at repo root
