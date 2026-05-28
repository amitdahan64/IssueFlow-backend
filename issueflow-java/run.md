# IssueFlow — Run Instructions

## Prerequisites

| Tool          | Version       | Notes |
|---------------|---------------|-------|
| **Java**      | **21 LTS**    | The Maven wrapper targets Java 21. JDK 25 has a Lombok-IDE compatibility issue (see _Troubleshooting_), but works at runtime with the bumped Lombok 1.18.42 declared in `pom.xml`. |
| Docker        | any recent    | For the bundled Postgres in `compose.yml`. |
| (bundled)     | Maven 3.9.x   | Use `./mvnw` — no separate install needed. |

Confirm Java:

```bash
$ java -version
openjdk version "21.0.x"
```

If `java -version` reports anything older than 21, export `JAVA_HOME` for the session (replace the path with one from `/usr/libexec/java_home -V` on macOS):

```bash
export JAVA_HOME="/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
```

---

## 1. Start the database

```bash
docker compose up -d
```

The bundled `compose.yml` boots a PostgreSQL 16 instance on `localhost:5432` with `issueflow / issueflow / issueflow` (user / password / db). The app's `application.yaml` is already wired to those credentials.

Verify:

```bash
docker ps | grep postgres
# or
docker compose logs db --tail=20
```

To stop:

```bash
docker compose down
# add -v if you want to wipe the volume
```

---

## 2. Build the project

```bash
./mvnw clean package
```

Hibernate manages the schema via `spring.jpa.hibernate.ddl-auto: update`, so tables are created automatically on first boot — no migration step needed.

---

## 3. Run the application

```bash
./mvnw spring-boot:run
# or, after the build step above:
java -jar target/issueflow-0.0.1-SNAPSHOT.jar
```

On first launch the app **seeds a bootstrap ADMIN user** so the demo flow works without manual SQL:

```
username: admin
password: admin12345
email:    admin@issueflow.local
role:     ADMIN
```

The defaults are configurable via `issueflow.bootstrap.admin-username / admin-password / admin-email` in `application.yaml` or as env vars. Re-runs are idempotent — the seeder skips if a user already exists with the configured username.

Health check:

```bash
curl http://localhost:8080/actuator/health
# {"status":"UP"}
```

---

## 4. Run the tests

```bash
./mvnw test
```

Tests use an in-memory H2 database in PostgreSQL-compatibility mode (`src/test/resources/application.yaml`), so the Docker DB does not need to be running for the test suite. The escalation scheduler is disabled in the `test` profile (`issueflow.escalation.enabled: false`) so tests can call `EscalationService.runOnce()` directly without competing with background ticks.

Expected output (last line):

```
[INFO] Tests run: 144, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

To run a single test class:

```bash
./mvnw test -Dtest=EndToEndFlowTest
```

---

## 5. Demo flow (`curl`)

After `./mvnw spring-boot:run` is up against the Dockerized Postgres:

```bash
# 1) Login as the seeded admin
TOKEN=$(curl -s -X POST localhost:8080/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin12345"}' | jq -r .accessToken)

# 2) Register a developer
curl -s -X POST localhost:8080/users \
  -H 'Content-Type: application/json' \
  -d '{"username":"alice","email":"a@x.com","fullName":"Alice","role":"DEVELOPER","password":"secret1"}'

# 3) Create a project (owner = ADMIN id; look it up first)
ADMIN_ID=$(curl -s localhost:8080/users \
  -H "Authorization: Bearer $TOKEN" | jq '.[] | select(.username=="admin") | .id')

curl -s -X POST localhost:8080/projects \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $TOKEN" \
  -d "{\"name\":\"Alpha\",\"description\":\"demo\",\"ownerId\":$ADMIN_ID}"

# 4) Create a ticket without an assignee — auto-assignment picks the least-loaded DEVELOPER (alice)
curl -s -X POST localhost:8080/tickets \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"title":"Fix login","description":"...","status":"TODO","priority":"HIGH","type":"BUG","projectId":1}'

# 5) Inspect workload
curl -s localhost:8080/projects/1/workload -H "Authorization: Bearer $TOKEN" | jq

# 6) Inspect audit log (filtered)
curl -s 'localhost:8080/audit-logs?entityType=TICKET&action=CREATE' \
  -H "Authorization: Bearer $TOKEN" | jq

# 7) Export to CSV
curl -s 'localhost:8080/tickets/export?projectId=1' \
  -H "Authorization: Bearer $TOKEN" -o tickets.csv
cat tickets.csv

# 8) Logout — denylists the token
curl -s -X POST localhost:8080/auth/logout -H "Authorization: Bearer $TOKEN"
```

---

## Troubleshooting

| Symptom | Cause / Fix |
|---|---|
| `mvnw: permission denied` | `chmod +x mvnw` |
| `Fatal error compiling: java.lang.ExceptionInInitializerError: com.sun.tools.javac.code.TypeTag :: UNKNOWN` during build | Building with JDK 24+. Use JDK 21 (see Prerequisites). |
| IDE shows hundreds of "cannot find symbol" errors on Lombok-generated getters/setters | IDE running the Lombok annotation processor under JDK 25 — `pom.xml` already overrides to Lombok 1.18.42 which fixes this; reload the IDE project after pulling. |
| Application fails to start with "Connection to localhost:5432 refused" | Docker Postgres isn't up. Run `docker compose up -d`. |
| Random "Generated security password" log line on startup | Spring Security default `UserDetailsServiceAutoConfiguration` kicked in — should never appear with our `SecurityConfig`. If you see it, check that `common/config/SecurityConfig.java` is on the classpath. |
| Tests pass locally but the embedded H2 schema looks wrong | Tests use `spring.jpa.hibernate.ddl-auto: update` — drop the H2 console state by re-running `./mvnw clean test`. |
