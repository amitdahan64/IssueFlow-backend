# AI Collaboration — Prompts & Setup

## Model

**Claude Opus 4.7** (`claude-opus-4-7`), via the Claude Code CLI inside a VS Code extension.

## Skills, Instructions, and Artefacts Committed to the Repo

| Path | Purpose |
|---|---|
| [`Guide.md`](Guide.md) | Per-feature implementation guide derived from the requirements PDF + the README contract. Loaded at the start of the planning step and referenced through every phase. |
| [`run.md`](run.md) | Setup / build / run / test / demo `curl` flow. |
| [`prompts.md`](prompts.md) | This file. |

No custom Claude skills, hooks, or sub-agent definitions were committed — the work used Claude Code's built-in **Plan mode** (which spawned a single read-only `Plan` sub-agent during the planning phase) and standard `Edit` / `Read` / `Bash` tool calls. Tool invocations and file writes are reflected in the resulting git history.

---

## Main Prompts (verbatim) — up to plan approval

### Prompt 1 — produce the implementation Guide

> In the TDP_issueflow_requirments pdf file you can find the requirments for this project. Go through them and create a thourough Guide.md for yourself for this project. specify endpoints, constrains and important notes for implementation. We will build it using java and spring boot framework. You can also use the README file for more context as it serve as the contract for this backend service we are building.

**What the model did:** read the PDF + skeleton README + `pom.xml` + `application.yaml`, then produced [`Guide.md`](Guide.md) — a one-file implementation contract enumerating every endpoint, every enum, every constraint (lifecycle, concurrency, mentions, escalation, auto-assignment), and the gotchas in the README (e.g. `POST /users/update/:id` is intentionally non-RESTful; the bundled `schema.sql`/`data.sql` reference a stale `task` table that would crash startup).

### Prompt 2 — produce the phased implementation plan

> Lets plan an implementation plan for this project, figure out what is the optimal sequence of implementation due to the known constrains mentioned in the pdf and the Guide.md, break it down to smaller tasks and phases, include some basic tests in the end of each phase.

**What the model did (Plan mode):**
1. Skipped the explicit "explore the codebase" phase (only a near-empty Spring Boot skeleton exists).
2. Launched **one read-only `Plan` sub-agent** with a self-contained brief: the requirements PDF, `Guide.md`, and a draft 12-phase sequence to validate. The sub-agent was asked to find ordering hazards, suggest a minimal demonstrable slice per phase, and surface spec ambiguities.
3. Integrated the agent's feedback into the final plan:
   - Pulled cross-cutting concerns up to Phase 0 (`AuditService` interface as a no-op bean, all enums, error handler with optimistic-lock + multipart handlers, `spring-retry` dep, JWT config keys).
   - Swapped Phase 6 ⇄ Phase 7: do ticket **dependencies** before comments so the `TicketService` guard chain is finalized once and never touched again.
   - Added **interface seams** in Phase 5 (`TicketAssignmentResolver`, `TicketTransitionGuard`) so Phase 6, 7, 8, 9 plug in via new beans rather than editing the service.
   - Resolved spec ambiguities up-front (e.g. `POST /users` is intentionally public per spec section 2.1's "Register a new user" language; auto-assign candidate pool is all DEVELOPERs system-wide; restore doesn't cascade to soft-deleted tickets).
4. Wrote the final plan to `/Users/amit/.claude/plans/lets-plan-an-implementation-distributed-jellyfish.md` and called `ExitPlanMode` to request approval.

**Approved as-is** with the noted edits, then implementation began at Phase 0.

---

## Notes on AI-assisted code quality

Every phase ended with `./mvnw test` green before moving on. The seams paid off concretely:

- **Phase 6 (dependencies)** added `DependencyBlockerGuard` as a second `TicketTransitionGuard` bean — zero edits to `TicketService` or `TicketController`.
- **Phase 8 (auto-assignment)** replaced the Phase 5 `DefaultTicketAssignmentResolver` with `AutoAssignService` (the only `TicketAssignmentResolver` bean) — `TicketService` only gained a 2-line AUTO_ASSIGN audit hook.
- **Phase 3 (real `AuditLogService`)** replaced the Phase-0 `NoOpAuditService` with no caller changes — every service had been injecting the `AuditService` interface since Phase 1.

Six bugs surfaced during phase test runs were fixed inline with explanatory commit-style code comments left in the source, not stripped out:
1. Stale `schema.sql`/`data.sql` referencing a non-existent `task` table → removed in Phase 0.
2. `@EnableJpaAuditing` on the main class crashed `@WebMvcTest` slices (empty JPA metamodel) → moved to a dedicated `JpaAuditingConfig`.
3. `@SQLRestriction` is bypassed by Hibernate's L1 cache when a soft-delete + read happen in the same transaction → added defensive `isDeleted()` check in `findById`.
4. `@Version` doesn't increment in shared-transaction tests until flush → `saveAndFlush` in `TicketService.update` (and `CommentService.update`).
5. `@Lob byte[]` on H2 in PG-compat mode misroutes to `INTEGER` → dropped `@Lob`; Hibernate 6 maps `byte[]` to `VARBINARY`/`BYTEA` natively.
6. MockMvc doesn't enforce `spring.servlet.multipart.max-file-size` → added a service-level `PayloadTooLargeException` → 413 for parity with the production servlet path.

Each fix is mentioned in the relevant phase summary in the conversation transcript.
