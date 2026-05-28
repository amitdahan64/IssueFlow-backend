# IssueFlow — Phased Implementation Plan

## Context

We're building the **IssueFlow** backend from a near-empty Spring Boot 3.4 / Java 21 skeleton at `/Users/amit/Desktop/פרוייקטים /IssueFlow backend/issueflow-java`. The requirements PDF and the `Guide.md` already document every endpoint and rule. This plan defines the **order** to build them in, the **interface seams** to design up-front so later phases don't force rework, and the **demonstrable slice** at each phase boundary.

The ordering is driven by hard dependencies:
- JWT auth depends on a persistable `User` (so Users come first, then Security).
- Audit logging depends on a current-user resolver (so audit comes after Security).
- Ticket lifecycle is touched by dependencies (DONE-block), auto-assign (create), and escalation (priority reset). To avoid editing Ticket service code three times, we **install interface seams in Phase 5** and let later phases plug in implementations.
- CSV import calls the same creation path as the API, so it depends on auto-assignment being live.
- Soft delete and `@Version` are cross-cutting → bake into `BaseEntity` in Phase 0.

## Default decisions for spec ambiguities

| # | Question | Default |
|---|---|---|
| 1 | Is `POST /users` JWT-protected? | **Public** — spec section 2.1 calls it "register". One ADMIN is seeded via `data.sql` so the demo flow works. |
| 2 | Password field on User? | **Yes**, accept `password` in `POST /users`, stored as BCrypt hash, never returned. |
| 3 | Auto-assign candidate pool / workload membership | **All DEVELOPER users system-wide**. Project membership isn't modeled in the spec (no join table). Workload endpoint lists every DEVELOPER + the project owner. |
| 4 | Project restore cascade tickets? | **No.** Tickets stay soft-deleted; restore individually. |
| 5 | Comment delete = hard delete? | **Yes** — soft delete scope per spec is tickets/projects only. |
| 6 | Escalation cadence semantics (LOW → CRITICAL takes ≥3 ticks) | **Idempotent step per tick**: one priority bump per run; `isOverdue` is set only on a tick where the ticket is already CRITICAL **and** still overdue. |
| 7 | CSV import + auto-assign | Imported rows with missing `assigneeId` go through the same auto-assign path as API creates. |

---

## Phase 0 — Bootstrap & cross-cutting scaffolding

**Why first**: getting `BaseEntity`, enums, error mapping, and config right now eliminates schema churn later.

Tasks:
- Delete stale `src/main/resources/schema.sql` and `data.sql` (they reference a `task` table — `spring.sql.init.mode: always` will crash startup otherwise).
- Replace `data.sql` with a seed file that inserts one ADMIN user (BCrypt hash) so login is demoable end-to-end.
- Add deps to `pom.xml`: `spring-boot-starter-security`, `io.jsonwebtoken:jjwt-api/impl/jackson`, `org.springframework.retry:spring-retry`, `org.springframework:spring-aspects` (retry proxies).
- Extend `application.yaml`: `security.jwt.secret`, `security.jwt.ttl-seconds`, `issueflow.escalation.fixed-delay-ms`, `issueflow.attachments.*`. Switch `spring.jpa.hibernate.ddl-auto` to `update`.
- `common/` package:
  - `BaseEntity` with `id`, `createdAt`, `updatedAt`, `version` (`@Version`), `deletedAt` (nullable). Use `@MappedSuperclass`.
  - All enums: `Role`, `TicketStatus`, `Priority`, `TicketType`, `AuditAction`, `AuditActor`, `EntityType`.
  - `ApiError` DTO + `GlobalExceptionHandler` (`@RestControllerAdvice`) — including `ObjectOptimisticLockingFailureException → 409` and `MaxUploadSizeExceededException → 413`.
  - **`AuditService` interface** + **no-op `@Primary` bean** in `common.audit`. All later services inject this from day one; Phase 3 replaces the bean with the real impl.
- Smoke check: `./mvnw spring-boot:run` boots, `GET /actuator/health` returns 200.

Tests: smoke test only (`@SpringBootTest` context loads, `GET /actuator/health` returns 200).

---

## Phase 1 — Users (no auth yet)

Tasks:
- `User` entity (extends BaseEntity, ignores `deletedAt`), unique on `username` and `email`, `passwordHash` column.
- Repository, service, controller for `GET /users`, `GET /users/{id}`, `POST /users`, `POST /users/update/{id}` (non-RESTful, per contract), `DELETE /users/{id}`.
- `@Valid` DTOs; BCrypt-hash the password on create; never include hash in responses.
- Inject the (still no-op) `AuditService` and call it on every state change.

Tests:
- `@WebMvcTest` for controller: happy-path + validation 400s.
- `@DataJpaTest` for repository: unique constraints, find-by-username.

---

## Phase 2 — Authentication (JWT)

Tasks:
- `SecurityConfig`: stateless session, `JwtAuthFilter` before `UsernamePasswordAuthenticationFilter`, BCrypt encoder bean.
- `JwtService`: sign HS256, include `sub=userId`, `username`, `role`, `jti`, `exp`.
- `TokenDenylist` entity (`jti`, `expiresAt`) + scheduled cleanup of expired entries.
- `AuthController`: `POST /auth/login`, `POST /auth/logout`, `GET /auth/me`.
- Permit-list: `POST /auth/login`, `POST /users`, `/actuator/health`. Everything else requires `Authorization: Bearer …`.
- `@PreAuthorize` infra ready via `@EnableMethodSecurity`.

Tests:
- Login returns JWT for valid creds, 401 for invalid.
- Protected endpoint without token → 401.
- Logout → subsequent calls with the same token → 401 (denylist hit).

---

## Phase 3 — Audit log (real implementation)

Tasks:
- `AuditLog` entity + repo.
- Real `AuditService` impl that resolves actor from `SecurityContextHolder` (or `SYSTEM` if no auth context).
- Replace the no-op bean with the real one — **no service code changes needed**.
- `AuditLogController`: `GET /audit-logs` with optional filters (`entityType`, `entityId`, `action`, `actor`).

Tests:
- Each user CRUD call leaves a row with matching action.
- Filter query by `entityType`, `action`, `actor`.

---

## Phase 4 — Projects + soft delete + workload (stub)

Tasks:
- `Project` entity with `@SQLRestriction("deleted_at IS NULL")`.
- CRUD: `GET /projects`, `GET /projects/{id}`, `POST /projects`, `PATCH /projects/{id}`, `DELETE /projects/{id}` (soft).
- ADMIN-only: `GET /projects/deleted`, `POST /projects/{id}/restore`.
- **Stub** `GET /projects/{projectId}/workload` returning empty list — full impl lands in Phase 8.
- Audit hooks on every state-changing call.

Tests:
- Soft-deleted project hidden from `GET /projects`, visible in `/deleted`.
- Restore moves it back.
- Non-ADMIN gets 403 on `/deleted` and `/restore`.

---

## Phase 5 — Tickets + lifecycle + concurrency (with interface seams)

**This is the highest-leverage phase. Get the seams right.**

Tasks:
- `Ticket` entity with `@Version`, `deletedAt`, `isOverdue` (default false), `dueDate` (nullable).
- Interface seams introduced **now**:
  - `TicketAssignmentResolver { Long resolve(TicketCreateDto dto, Project project); }` — default: returns `dto.assigneeId`.
  - `TicketTransitionGuard { void verify(Ticket existing, TicketPatchDto patch); }` — default enforces forward-only status + no edit when DONE.
- All CRUD endpoints per README contract.
- PATCH body requires a `version` field; `ObjectOptimisticLockingFailureException` → 409.
- Audit hooks.

Tests:
- Lifecycle: forward transition ok; backward → 4xx; edit DONE ticket → 4xx.
- Optimistic lock: two PATCHes with same stale `version` → second → 409.
- Manual priority change clears `isOverdue`.

---

## Phase 6 — Ticket dependencies (formerly Phase 7)

**Swapped earlier than comments**: finalize ticket guard chain once, never touch again.

Tasks:
- `TicketDependency` entity (`ticketId`, `blockerTicketId`, unique composite).
- Endpoints: `POST /tickets/{ticketId}/dependencies`, `GET /tickets/{ticketId}/dependencies`, `DELETE /tickets/{ticketId}/dependencies/{blockerId}`.
- Constraint: both tickets must exist and belong to the same project.
- New `TicketTransitionGuard` bean (`DependencyBlockerGuard`): if `patch.status == DONE`, all blockers must be DONE.

Tests:
- Cannot PATCH ticket to DONE while blocker not DONE; can after blocker is DONE.
- Same-project enforcement.

---

## Phase 7 — Comments + mentions

Tasks:
- `Comment` entity with `@Version`, `CommentMention` join entity (unique on `commentId + mentionedUserId`).
- `MentionParser`: regex `@([A-Za-z0-9_.-]+)`, case-insensitive username lookup.
- Endpoints: `GET /tickets/{ticketId}/comments`, `POST`, `PATCH`, `DELETE`.
- `GET /users/{userId}/mentions?page=&pageSize=` — paginated, newest first.
- On PATCH: re-evaluate mention set (diff old vs new).

Tests:
- Mention regex; case-insensitive username match.
- Update re-evaluates: add new mention, remove old.
- Two concurrent PATCHes → second → 409.
- Pagination on `/users/{id}/mentions`.

---

## Phase 8 — Workload + auto-assignment

Tasks:
- Implement `GET /projects/{projectId}/workload` (replace Phase 4 stub): count open tickets per DEVELOPER + project owner, sort ASC.
- `AutoAssignService` implements `TicketAssignmentResolver`: when `assigneeId == null`, pick least-loaded DEVELOPER (tie-break by oldest `createdAt`).
- Audit row with `actor=SYSTEM`, `action=AUTO_ASSIGN`.

Tests:
- POST ticket with no assignee → least-loaded DEVELOPER picked.
- Tie-break by registration order.
- No DEVELOPERs → ticket created with `assigneeId=null`.
- Workload reflects new counts.

---

## Phase 9 — Auto-escalation scheduler

Tasks:
- `EscalationScheduler` with `@Scheduled(fixedDelayString)`.
- Query: tickets where `dueDate < now() AND status != DONE AND deleted_at IS NULL`.
- Per tick per ticket: bump priority one rung; if already CRITICAL → set `isOverdue = true`.
- `@Retryable(ObjectOptimisticLockingFailureException)` + `REQUIRES_NEW` transaction per ticket.
- Audit `actor=SYSTEM, action=AUTO_ESCALATE`.

Tests:
- LOW → MEDIUM → HIGH → CRITICAL → isOverdue across ticks.
- CRITICAL + isOverdue → no further change.
- DONE tickets and tickets without `dueDate` ignored.

---

## Phase 10 — Attachments

Tasks:
- `Attachment` entity (`ticketId`, `filename`, `contentType`, `sizeBytes`, `bytes` BYTEA, `uploadedBy`).
- `POST /tickets/{ticketId}/attachments`: multipart `file`; validate content-type whitelist; 10MB cap.
- `DELETE /tickets/{ticketId}/attachments/{attachmentId}`.
- No `@Lob` on `byte[]` — Hibernate 6 maps natively to BYTEA.

Tests:
- PNG upload → 200.
- File over 10MB → 413.
- Disallowed MIME → 400.

---

## Phase 11 — CSV export / import

Tasks:
- `GET /tickets/export?projectId={id}`: stream `text/csv`, columns `id,title,description,status,priority,type,assigneeId`, `Content-Disposition: attachment`. Quote all fields.
- `POST /tickets/import`: multipart `file` + form field `projectId`. Per-row validation + `TicketService.create` (auto-assign + audit happen automatically). Per-row failures collected without aborting batch.
- Response: `{ "created": N, "failed": N, "errors": [{ "row": N, "message": "..." }] }`.

Tests:
- Round-trip export → import into a second project: counts match.
- Malformed row → captured in `errors`, others still imported.
- CSV with commas/quotes/newlines in description → round-trips correctly.

---

## Phase 12 — Polish

Tasks:
- Full integration test covering: login → create project → create ticket → comment + mention → block + transition → escalation tick → audit query → export → import → soft delete + restore.
- Write `run.md`: prerequisites, docker compose, build, run, test, curl demo flow, troubleshooting.
- Write `prompts.md`: model info + main prompts used.
- README touch-up.

Tests: one large `@SpringBootTest` end-to-end test. `./mvnw test` green across all 144 tests.

---

## Sequencing summary

```
0 Bootstrap     →   1 Users    →   2 Auth    →   3 Audit
                                                    ↓
4 Projects+soft   →   5 Tickets (seams!)   →   6 Dependencies
                                                    ↓
                          7 Comments+mentions   →   8 Workload+auto-assign
                                                    ↓
                          9 Escalation   →   10 Attachments   →   11 CSV
                                                                    ↓
                                                                12 Polish
```

Key invariants:
1. Cross-cutting concerns (`@Version`, soft delete, error mapping, audit interface) exist before any feature uses them.
2. Ticket service code is touched exactly **once after Phase 5**: Phase 6 adds a guard bean; Phase 8 swaps the assignment resolver; Phase 9 only reads/writes tickets.
3. Every phase ends with a curl-able demo + green tests for that phase's surface.
