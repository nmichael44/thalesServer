# ThalesServer — Full Project Code Review

This review covers all 89 files across `src/main/scala`, `src/main/resources`, `src/test/scala`, `build.sbt`, and CI
configuration. Findings are grouped by severity and category, with links to the exact files and lines.

> [!IMPORTANT]
> No code has been modified. All items below are documented for discussion so we can decide which ones you want to
> address and in what order.

---

## 1. Critical & High-Severity Issues (Bugs & Security)

### 1.1 [CRITICAL] Predictable Non-Cryptographic PRNG (`SplittableRandom`) Used for Password Reset Tokens
- **Where:**
  - [`UUIDGenerator.populateQueue`](../src/main/scala/app/uuid/UUIDGenerator.scala#L38-L48)
  - [`InitiateRecoveryOfUserPassword.genTokens`](../src/main/scala/app/workerTasks/InitiateRecoveryOfUserPassword.scala#L44-L49)
  - [`ThalesServer.resolveTraceId`](../src/main/scala/app/ThalesServer.scala#L227-L234)
- **Issue:**
  - [`UUIDGenerator`](../src/main/scala/app/uuid/UUIDGenerator.scala#L45) uses `java.util.SplittableRandom`, which is
    a **non-cryptographic** PRNG with a 64-bit internal state and invertible mixing function.
  - [`InitiateRecoveryOfUserPassword`](../src/main/scala/app/workerTasks/InitiateRecoveryOfUserPassword.scala#L46) uses
    `uuidGen.generateUUIDAsString` to generate the secret password-reset token (`rawToken`) sent to the user's email.
  - Worse, the **same pool of 8 `SplittableRandom` instances** is used by
    [`ThalesServer.onTraceIdNotFound`](../src/main/scala/app/ThalesServer.scala#L227) to generate `X-Request-ID`
    response headers whenever an unauthenticated client omits `X-Request-ID`.
  - An attacker can send unauthenticated HTTP requests, observe the returned `X-Request-ID` headers, recover the
    internal state of all 8 `SplittableRandom` generators, call `initiateRecoveryOfUserPassword` for `neo`, and
    compute the exact password reset token.
- **Recommendation:**
  Use `java.security.SecureRandom` (or `UUID.randomUUID()`, which is backed by `SecureRandom`) for security-sensitive
  tokens, or separate non-cryptographic trace ID generation from cryptographic token generation.

---

### 1.2 [CRITICAL] `application-prod.conf` Crashes on Startup When `APP_ENV=prod`
- **Where:**
  - [`application-prod.conf`](../src/main/resources/application-prod.conf#L16-L26)
  - [`AppConfig`](../src/main/scala/app/Config/AppConfigUtils.scala#L96-L103)
  - [`ThalesServer.readConfigFile`](../src/main/scala/app/ThalesServer.scala#L296-L305)
- **Issue:**
  [`application-prod.conf`](../src/main/resources/application-prod.conf#L16-L26) contains leftover configuration keys
  (`mem-cache-config.director-mem-cache-config`, `actor-mem-cache-config`, `movie-mem-cache-config`) that do not exist
  on [`AppConfig`](../src/main/scala/app/Config/AppConfigUtils.scala#L96-L103). PureConfig's derived `ConfigReader`
  rejects unknown keys by default (`UnknownKey`), so starting the server with `APP_ENV=prod` immediately fails with
  `ConfigReaderException`.
- **Recommendation:**
  Remove the stale `mem-cache-config` block from
  [`application-prod.conf`](../src/main/resources/application-prod.conf#L16-L26) and add a unit test that loads both
  `dev` and `prod` configurations.

---

### 1.3 [HIGH] Validating a Valid JWT With `< 10s` Remaining TTL Crashes With HTTP 500
- **Where:**
  - [`AuthServiceLive.addUserToCache`](../src/main/scala/app/serviceslive/AuthServiceLive.scala#L144-L157)
  - [`MemCache.putAux`](../src/main/scala/app/mem_caches/MemCache.scala#L75-L88)
- **Issue:**
  - [`MemCache.putAux`](../src/main/scala/app/mem_caches/MemCache.scala#L75-L88) raises an `IllegalArgumentException`
    in `F` whenever `duration < 10.seconds` (`ItemMinimumAllowedDuration`).
  - When a valid JWT is not currently in `authUserMemCache` (e.g., evicted due to capacity=100 or after a server
    restart) and is validated via
    [`authenticateAndCacheUser`](../src/main/scala/app/serviceslive/AuthServiceLive.scala#L152-L158) with fewer than
    10 seconds left before expiry (`secs = authUser.expiresAt - now < 10`),
    [`addUserToCache`](../src/main/scala/app/serviceslive/AuthServiceLive.scala#L144-L150) calls
    `authUserMemCache.put(token, authUser, ttlDuration)`.
  - Because `addUserToCache` is lifted via `EitherT.liftF`
    ([line 156](../src/main/scala/app/serviceslive/AuthServiceLive.scala#L156)), the `IllegalArgumentException` is
    raised as an unhandled error in `F` rather than caught in `EitherT`, causing the request to fail with
    **500 Internal Server Error**.
- **Recommendation:**
  In [`addUserToCache`](../src/main/scala/app/serviceslive/AuthServiceLive.scala#L144-L150), only cache the token if
  `secs >= 10` (or `ttlDuration.compareTo(MemCache.ItemMinimumAllowedDuration) >= 0`).

---

### 1.4 [HIGH] Stale `authUserMemCache` Entries Are Never Invalidated on Security State Changes
- **Where:**
  - [`AuthServiceLive`](../src/main/scala/app/serviceslive/AuthServiceLive.scala#L87-L90)
  - [`MemCache`](../src/main/scala/app/mem_caches/MemCache.scala#L22-L114)
- **Issue:**
  - [`MemCache`](../src/main/scala/app/mem_caches/MemCache.scala#L22-L114) has no `remove` / `invalidate` methods.
  - Once a JWT is cached in `authUserMemCache` (for up to 2 hours, `expiration-period-in-seconds = 7200`),
    [`AuthServiceLive.validateToken`](../src/main/scala/app/serviceslive/AuthServiceLive.scala#L132-L138) returns the
    cached `AuthenticatedUser` (and its cached `permissions` `BitSet`) without consulting the database.
  - Even if a token isn't in the cache,
    [`authenticateAndCacheUser`](../src/main/scala/app/serviceslive/AuthServiceLive.scala#L152-L158) only decodes the
    JWT claims without checking the database.
  - As a result, when an admin disables a user, calls
    [`setMustResetUserPassword(userId, true)`](../src/main/scala/app/workerTasks/SetMustResetUserPassword.scala#L19-L27),
    or changes a user's roles via
    [`updateUserRolesById`](../src/main/scala/app/workerTasks/UpdateUserRolesById.scala#L24-L39)—or when a user resets
    their password—any previously issued JWT continues to work with its old permissions for up to 2 hours on all
    endpoints (except `renewJwtToken`, which queries the DB).
- **Recommendation:**
  Either support user-level cache invalidation / user status checks, or document clearly if stateless 2-hour JWT
  validity is an intentional trade-off.

---

### 1.5 [HIGH] Plaintext Bearer JWT Tokens Logged at `INFO` Level
- **Where:**
  - [`AuthServiceLive.createToken`](../src/main/scala/app/serviceslive/AuthServiceLive.scala#L88)
  - [`UserServicesIntegrationTest`](../src/test/scala/app/entrypoints/UserServicesIntegrationTest.scala#L135)
- **Issue:**
  [`AuthServiceLive.createToken`](../src/main/scala/app/serviceslive/AuthServiceLive.scala#L88) logs:
  ```scala
  U.logi(s"Caching user ($userId) in MemCache.  Token was: '$token'.")
  ```
  Every issued and renewed JWT bearer token is written in plaintext to application logs. Anyone with access to logs
  can hijack any active user session.
- **Recommendation:**
  Remove the raw token from the log message (or log only the `jti` / user ID).

---

### 1.6 [HIGH] `InitiateRecoveryOfUserPassword` Breaks Transactional Outbox Atomicity & Skips `user.enabled` Check
- **Where:**
  - [`InitiateRecoveryOfUserPassword`](../src/main/scala/app/workerTasks/InitiateRecoveryOfUserPassword.scala#L25-L75)
  - [`JobSpecs.InitiateRecoveryOfUserPasswordError`](../src/main/scala/app/JobSpecs.scala#L101-L104)
- **Issue:**
  1. **Two separate transactions:** Line 58 runs `initiateRecoveryOfUserPasswordDbProgram(...).transact(xa)` to insert
     into `ResetUserPasswordTokens`, and then lines 59–71 call `emailService.sendEmail(...)`, which starts a **second
     transaction** ([`EmailServiceLive.scala:23-25`](../src/main/scala/app/serviceslive/EmailServiceLive.scala#L23-L25))
     to insert into `EmailOutbox`. This defeats the purpose of the transactional outbox pattern: if the second
     transaction fails or the server crashes between the two transactions, the token is committed to the DB, but the
     recovery email is never queued.
  2. **Disabled user check missing:**
     [`InitiateRecoveryOfUserPasswordError`](../src/main/scala/app/JobSpecs.scala#L101-L104) defines
     `case UserNotEnabled`, but
     [`InitiateRecoveryOfUserPassword`](../src/main/scala/app/workerTasks/InitiateRecoveryOfUserPassword.scala#L25-L42)
     never checks `if !user.enabled`. Disabled users can still generate password reset tokens and receive reset emails.
  3. **Multiple active tokens per user:** Generating a new recovery token does not delete existing recovery tokens for
     that `userId` in `ResetUserPasswordTokens`.

---

## 2. Medium-Severity Bugs & Domain Logic Issues

### 2.1 `userPasswordUpdateTime` Column in `Users` Is Never Updated on Password Reset
- **Where:**
  - [`RepositoryServiceLive.updateUserPasswordInDb`](../src/main/scala/app/serviceslive/RepositoryServiceLive.scala#L201-L203)
  - [`AppSchema.sql`](../src/main/resources/AppSchema.sql#L37)
- **Issue:**
  `updateUserPasswordInDb` executes:
  ```scala
  sql"""update Users set hashedPassword = ${hashedPassword.value}, mustResetPassword = false
        where userId = ${userId.value}""".exec
  ```
  It never updates `userPasswordUpdateTime`, so `userPasswordUpdateTime` remains permanently equal to
  `userCreationTime` even after [`ResetMyPassword`](../src/main/scala/app/workerTasks/ResetMyPassword.scala#L42) or
  [`ResetUserPassword`](../src/main/scala/app/workerTasks/ResetUserPassword.scala#L34).

---

### 2.2 `ResetUserPassword` Runs Expensive Argon2 Hashing Before Validating the Reset Token
- **Where:**
  - [`ResetUserPassword.resetUserPassword`](../src/main/scala/app/workerTasks/ResetUserPassword.scala#L48-L58)
- **Issue:**
  `resetUserPassword` is an **unauthenticated** endpoint. On
  [line 53](../src/main/scala/app/workerTasks/ResetUserPassword.scala#L53), it hashes `newPassword` with Argon2id
  (`memory = 16384, iterations = 4`) **before** checking whether `hashedToken` even exists or is expired in
  `ResetUserPasswordTokens` ([line 56](../src/main/scala/app/workerTasks/ResetUserPassword.scala#L56)). An
  unauthenticated attacker can flood `resetUserPassword` with bogus tokens and valid-format passwords to exhaust
  CPU/memory via Argon2 hashing.
- **Recommendation:**
  Check token existence/expiration (and user enabled status) before running Argon2 hashing, or validate the token
  first.

---

### 2.3 `serverState.lastAccess` Unbounded Growth & Misleading `FetchAllLiveSessions` Semantics
- **Where:**
  - [`ServerStateLive`](../src/main/scala/app/serviceslive/ServerStateLive.scala#L16-L24)
  - [`LoginServicesSmithyEp.updateLastAccess`](../src/main/scala/app/entrypoints/LoginServicesSmithyEp.scala#L18-L23)
  - [`FetchAllLiveSessions`](../src/main/scala/app/workerTasks/FetchAllLiveSessions.scala#L20-L34)
- **Issue:**
  1. `serverState.lastAccess` (`Ref[F, Map[UserId, Instant]]`) is only updated upon login
     ([`LoginServicesSmithyEp.scala:40`](../src/main/scala/app/entrypoints/LoginServicesSmithyEp.scala#L40)), never on
     authenticated API calls or token renewals.
  2. Entries in `lastAccess` are **never evicted or expired**. Every user who has ever logged in since server startup
     remains in `lastAccess` forever and is returned by `fetchAllLiveSessions` as a "live session" even days after
     their token expired.

---

### 2.4 `UpdateUserRolesById` Performs Input Validation Before Authorization & Disallows Clearing All Roles
- **Where:**
  - [`UserServicesSmithyEp.updateUserRolesById`](../src/main/scala/app/entrypoints/UserServicesSmithyEp.scala#L253-L263)
- **Issue:**
  1. When `roleIds` is empty, `updateUserRolesById` raises `epErrors.invalidInputParameters` (HTTP 400) **before**
     calling `jobHandler.jobHandlerWithAuth` (which checks `UpdateUserRolesByIdPermissionsAlg` and logs the trace ID).
     An unauthorized user receives a 400 instead of 403 Forbidden, and the rejection happens outside `JobHandler`.
  2. Requiring `NonEmptyVector.fromVector(roleIds)` makes it impossible to strip all roles from a user (even though
     the Smithy contract takes a potentially empty `Vector[RoleId]`).

---

### 2.5 `DomainEvent.PasswordReset` Is Defined in `AuditLogUtils` Policy But Never Emitted
- **Where:**
  - [`AuditLogUtils.DomainEvent`](../src/main/scala/app/audit_log/AuditLogUtils.scala#L13-L18)
  - [`HttpWorker.resultToDomainEventMap`](../src/main/scala/app/HttpWorker.scala#L104-L123)
- **Issue:**
  `DomainEvent.PasswordReset(userId: UserId)` is defined on
  [line 16 of `AuditLogUtils.scala`](../src/main/scala/app/audit_log/AuditLogUtils.scala#L16), but
  `HttpWorker.resultToDomainEventMap` only maps `CreateUserRequest`, `CreateRoleRequest`, and `LoginRequest`. Password
  resets via `ResetMyPassword` and `ResetUserPassword` never emit an audit log event.

---

### 2.6 Unsafe `Map.apply` in `RepositoryServiceLive.fetchEligibleEmailsFromOutbox`
- **Where:**
  - [`RepositoryServiceLive.fetchEligibleEmailsFromOutbox`](../src/main/scala/app/serviceslive/RepositoryServiceLive.scala#L300-L304)
- **Issue:**
  [Line 303](../src/main/scala/app/serviceslive/RepositoryServiceLive.scala#L303) calls
  `val emailRecs = groupedRecipients(emailId)`. If an `EmailOutbox` row ever lacks corresponding `EmailRecipients`
  rows in the DB, `groupedRecipients(emailId)` throws `NoSuchElementException`, aborting the entire batch of 128
  emails in `EmailOutboxWorker`. Using `groupedRecipients.getOrElse(emailId, Vector.empty)` avoids crashing the sweep.

---

### 2.7 `CreateUser` Ignores Fields Present on the Smithy `User` Input & Weakly Validates Inputs
- **Where:**
  - [`CreateUser.validateUserParameters`](../src/main/scala/app/workerTasks/CreateUser.scala#L27-L69)
- **Issue:**
  1. The Smithy `User` type includes `creationTime`, `userPasswordUpdateTime`, and `creatingUserId`, which callers
     must supply in the JSON payload, yet [`CreateUser`](../src/main/scala/app/workerTasks/CreateUser.scala#L53-L65)
     silently ignores all three and overwrites them with `wu.getNow` and `j.creatingUserId`.
  2. `validateUserParameters` only checks `s.nonEmpty` (not `!s.isBlank`), allowing whitespace-only strings (`"   "`)
     for `loginName`, `firstName`, `lastName`, `email`, and `phone`. Furthermore, `jmail` is declared in
     [`build.sbt`](../build.sbt#L64) but never used to validate `user.email` (or `EmailServiceLive` recipients).

---

### 2.8 Middleware Ordering: `EntityLimiter` Is Outside `traceIdMiddleware`
- **Where:**
  - [`ThalesServer.createHttpApp`](../src/main/scala/app/ThalesServer.scala#L357-L365)
  - [`ThalesServer.mkHttpApp`](../src/main/scala/app/ThalesServer.scala#L248-L254)
- **Issue:**
  `createHttpApp` wraps `mkHttpApp` with `GZip(EntityLimiter(httpApp, maxPayloadSize))`. When a request payload
  exceeds 2 MB, `EntityTooLarge` unwinds *out* of `traceIdMiddleware`
  ([line 240](../src/main/scala/app/ThalesServer.scala#L240)) before `EntityLimiter` catches it and turns it into a
  `413 Payload Too Large` response, so 413 responses omit the `X-Request-ID` header.

---

### 2.9 Unsafe `.asInstanceOf` in `ThalesServer.createUUIDScope`
- **Where:**
  - [`ThalesServer.createUUIDScope`](../src/main/scala/app/ThalesServer.scala#L414-L419)
  - [`TraceIdScope`](../src/main/scala/app/TraceIdScope.scala#L11-L20)
- **Issue:**
  [`TraceIdScope.fromIOLocal`](../src/main/scala/app/TraceIdScope.scala#L12) is hardcoded to `IO`, and
  [`createUUIDScope[F[_]: Async]`](../src/main/scala/app/ThalesServer.scala#L414-L419) casts
  `Resource[IO, TraceIdScope[IO, Option[String]]]` to `Resource[F, TraceIdScope[F, Option[String]]]` via
  `.asInstanceOf`. Calling `ThalesServer.applicationResource[F]` with any `F` other than `IO` will throw a
  `ClassCastException` at runtime.

---

### 2.10 Inconsistent Clock Abstraction Usage Across Services
- **Where:**
  - [`EmailServiceLive.sendEmail`](../src/main/scala/app/serviceslive/EmailServiceLive.scala#L22)
  - [`EmailOutboxWorker.create`](../src/main/scala/app/EmailOutboxWorker.scala#L34)
  - [`AuthServiceLive.decodeWithOpts`](../src/main/scala/app/serviceslive/AuthServiceLive.scala#L93-L95)
- **Issue:**
  Although [`ClockService[F]`](../src/main/scala/app/services/ClockService.scala) exists in
  [`AppDependencies`](../src/main/scala/app/AppDependencies.scala#L18), `EmailServiceLive` and `EmailOutboxWorker` call
  `async.realTimeInstant` directly instead of using `ClockService`, and `Jwt.decode` in `AuthServiceLive` uses
  `java.time.Clock.systemUTC()` internally instead of `clockService`.

---

## 3. Build, Configuration & Code Hygiene

### 3.1 Unused Dependencies in `build.sbt`
In [`build.sbt`](../build.sbt):
1. `val alloyCoreVersion = "0.3.38"` ([line 21](../build.sbt#L21)) — unused version val.
2. `"org.tpolecat" %% "doobie-specs2" % doobieVersion` ([line 50](../build.sbt#L50)) — unused dependency, and placed
   in default (`compile`) scope rather than `Test`, bloating the assembly JAR with Specs2.
3. `"com.github.cb372" %% "cats-retry" % catsRetryVersion` ([line 46](../build.sbt#L46)) — never imported or used.
4. `"com.github.eikek" %% "emil-common"` & `"emil-javamail"` ([lines 62–63](../build.sbt#L62-L63)) — never imported or
   used (`EmailOutboxWorker` currently logs `[OUTBOX DISPATCH]`).
5. `"com.sanctionco.jmail" % "jmail" % jMailVersion` ([line 64](../build.sbt#L64)) — never imported or used.
6. Untracked `org/` directory in workspace root (seen in `git status`).

### 3.2 Dead Code, Duplication & Minor Cleanup in `src/main/scala`
- **[`EntryPointErrors.scala`](../src/main/scala/app/entrypoints/EntryPointErrors.scala):**
  - Duplicate error methods:
    - `authorizationError` ([line 24](../src/main/scala/app/entrypoints/EntryPointErrors.scala#L24)) vs unused
      `userForbiddenFromCallingEntryPoint` ([line 47](../src/main/scala/app/entrypoints/EntryPointErrors.scala#L47))
    - `usersPasswordIsInvalid` ([line 28](../src/main/scala/app/entrypoints/EntryPointErrors.scala#L28)) vs unused
      `passwordIsInvalid` ([line 74](../src/main/scala/app/entrypoints/EntryPointErrors.scala#L74))
    - `userMustLoginAgainTokenExpired` ([line 38](../src/main/scala/app/entrypoints/EntryPointErrors.scala#L38)) vs
      unused `renewalTimeHasExpired` ([line 102](../src/main/scala/app/entrypoints/EntryPointErrors.scala#L102))
    - `invalidOrMissingResetPasswordToken` ([line 33](../src/main/scala/app/entrypoints/EntryPointErrors.scala#L33))
      vs unused `resetPasswordTokenMissing` ([line 79](../src/main/scala/app/entrypoints/EntryPointErrors.scala#L79))
    - Unused `userIsUnAuthenticated` ([line 19](../src/main/scala/app/entrypoints/EntryPointErrors.scala#L19))
- **Duplicate Duration Formatting:**
  - [`ExtensionMethodUtils.toReadableString`](../src/main/scala/app/ThalesUtils/ExtensionMethodUtils.scala#L48-L63)
    and [`TimeUtils.durationToString`](../src/main/scala/app/ThalesUtils/TimeUtils.scala#L6-L36) both format
    `java.time.Duration`.
- **Duplicate Log Values in [`ResetMyPassword`](../src/main/scala/app/workerTasks/ResetMyPassword.scala#L18-L28):**
  - Redefines `logFetchingUserFromDb`, `logCheckingValidityOfNewPassword`, and `logComputingHashAndUpdatingDb` locally
    instead of using the identical fields on
    [`WorkerTaskUtils`](../src/main/scala/app/workerTasks/WorkerTaskUtils.scala#L55-L65) (which
    [`ResetUserPassword`](../src/main/scala/app/workerTasks/ResetUserPassword.scala#L50-L55) uses).
- **Unused Vals, Methods & Parameters:**
  - [`EmailOutboxWorker.PollingInterval`](../src/main/scala/app/EmailOutboxWorker.scala#L20) and
    [`BaseBackoffSeconds`](../src/main/scala/app/EmailOutboxWorker.scala#L22)
  - [`MemCache.getSize`](../src/main/scala/app/mem_caches/MemCache.scala#L205-L207) and
    [`MemCache.reportSize`](../src/main/scala/app/mem_caches/MemCache.scala#L209-L216)
  - [`ThalesServer.loge`](../src/main/scala/app/ThalesServer.scala#L260-L262),
    [`ThalesServer.logi(uuid, s)`](../src/main/scala/app/ThalesServer.scala#L264-L266),
    [`ThalesServer.AppVersion`](../src/main/scala/app/ThalesServer.scala#L286), and unused `deps` parameter in
    [`ThalesServer.createTopic`](../src/main/scala/app/ThalesServer.scala#L469)
  - [`HttpWorker.JobExecutor.apiClient`](../src/main/scala/app/HttpWorker.scala#L32)
  - [`RenewJwtToken`](../src/main/scala/app/workerTasks/RenewJwtToken.scala#L12-L15): unused constructor parameters
    `repoService`, `xa`, `wu` and unused local `val userId`
    ([line 30](../src/main/scala/app/workerTasks/RenewJwtToken.scala#L30))
  - [`FetchAllLiveSessions`](../src/main/scala/app/workerTasks/FetchAllLiveSessions.scala#L18): unused constructor
    parameter `wu`
  - [`RepositoryService.UpdateUserPasswordError`](../src/main/scala/app/services/RepositoryService.scala#L28-L30):
    unused enum
  - [`DbUtils.scala`](../src/main/scala/app/ThalesUtils/DbUtils.scala#L67-L99):
    `extension [K, V: Read](sql: Fragment)` attaches unused type parameters `[K, V: Read]` to `toVec`, `toOpt`,
    `toSet`, `toUnique`, `exec`, and `execToUnit`; `toGroupedMapForV`
    ([line 111](../src/main/scala/app/ThalesUtils/DbUtils.scala#L111)) is unused
  - [`FetchUserRoleIds`](../src/main/scala/app/workerTasks/FetchUserRoleIds.scala#L13-L41): missing `private` on
    constructor and `create` returns concrete `FetchUserRoleIds[F]` instead of
    `WorkerTask[F, FetchUserRoleIdsRequest]`
  - [`AuthConfig`](../src/main/scala/app/Config/AppConfigUtils.scala#L63-L71): only config case class missing
    `derives ConfigReader`
  - Typos: [`Permissions.scala:110`](../src/main/scala/app/auth/Permissions.scala#L110) (`"defers"` -> `"differs"`),
    [`EntryPointUtils.scala:4`](../src/main/scala/app/entrypoints/EntryPointUtils.scala#L4)
    (`entryPont` -> `entryPoint`), [`.scalafmt.conf:17`](../.scalafmt.conf#L17)
    (`regex = "package*"` -> `"^package"`).

---

## 4. Test Suite Review & Coverage Gaps

### 4.1 Test Reliability, Isolation & Hygiene Issues
1. **`TestUtils.setEnvVariables` Overwrites System Properties Globally
   ([`TestUtils.scala:30-46`](../src/test/scala/app/entrypoints/TestUtils.scala#L30-L46)):**
   - Sets hardcoded JVM system properties (`DB_USERNAME_PASSWORD`, `JWT_SECRET_KEY`, `KEYSTORE_PASSWORD`, etc.).
     Because [`ThalesServer.readConfigFile`](../src/main/scala/app/ThalesServer.scala#L297) prioritizes
     `ConfigSource.systemProperties` over environment variables in `application-dev.conf`,
     `TestUtils.setEnvVariables` overrides any environment variables configured in CI or the shell.
2. **All Integration Tests Bind to Fixed Privileged Port `443`
   ([`TestUtils.scala:61`](../src/test/scala/app/entrypoints/TestUtils.scala#L61),
   [`application.conf:5`](../src/main/resources/application.conf#L5)):**
   - Requires privileged port binding (`net.ipv4.ip_unprivileged_port_start=0` in CI) and prevents running tests if
     port 443 is in use. Using `port = 0` in tests and reading `server.baseUri` (like
     `ExternalApiClientServiceLiveTest` does) would eliminate port conflicts and privilege requirements.
3. **Timing-Sensitive Exact Assertions in
   [`QueueFullIntegrationTest.scala:102-105`](../src/test/scala/app/entrypoints/QueueFullIntegrationTest.scala#L102-L105):**
   - Fires 15 concurrent requests against 4 workers + 4 queue slots with a `600ms` delay and asserts **exact** counts
     (`queueFullResults.size shouldBe 7` and `processedResults.size shouldBe 8`). If connection setup / TLS handshakes
     across the 15 fibers spread over `600ms` under heavy CI load, a worker finishes early and processes a 9th
     request, causing a flaky failure.
4. **Missing `.guarantee(...)` Cleanup in
   [`PasswordResetIntegrationTest.scala:160-166`](../src/test/scala/app/entrypoints/PasswordResetIntegrationTest.scala#L160-L166)
   and
   [`EmailServiceLiveIntegrationTest.scala:223-224`](../src/test/scala/app/serviceslive/EmailServiceLiveIntegrationTest.scala#L223-L224):**
   - If an assertion fails mid-test in `PasswordResetIntegrationTest`, `neo`'s password is left modified in the
     database, breaking subsequent test suites!
   - Also, `PasswordResetIntegrationTest` triggers `initiateRecoveryOfUserPassword`, which inserts an email row into
     `EmailOutbox` that is never cleaned up by the test.
5. **Leftover Debug Logging & Dead Code in Tests:**
   - [`UserServicesIntegrationTest.scala:133-138`](../src/test/scala/app/entrypoints/UserServicesIntegrationTest.scala#L133-L138)
     wraps the test client in `debugClient` that prints `Request Headers` and `Response Headers` on all 480 requests.
   - [`UserServicesIntegrationTest.scala:181, 184`](../src/test/scala/app/entrypoints/UserServicesIntegrationTest.scala#L181-L184)
     calls `fetchAllLiveSessions(userServices)` twice in the `tasks` vector.
   - [`LoginServicesIntegrationTest.scala:41`](../src/test/scala/app/entrypoints/LoginServicesIntegrationTest.scala#L41)
     calls `e.printStackTrace()`.
   - [`TestUtils.scala:117-165`](../src/test/scala/app/entrypoints/TestUtils.scala#L117-L165) contains unused
     `resetDatabasePSql` with hardcoded `"D:\\Program Files\\PostgreSQL\\15\\bin\\psql.exe"`.

### 4.2 Major Test Coverage Gaps
1. **[`MemCache`](../src/main/scala/app/mem_caches/MemCache.scala) (0 unit tests):**
   - Even though `getInternalCacheState` ([line 109](../src/main/scala/app/mem_caches/MemCache.scala#L109)) and
     `CacheElem` ([line 135](../src/main/scala/app/mem_caches/MemCache.scala#L135)) have comments stating they are
     exposed for unit tests, there are no `MemCache` tests in `src/test`.
2. **Authentication (HTTP 401) & Authorization (HTTP 403) Rejection Paths:**
   - No tests verify calling protected endpoints without a token, with an invalid/expired token, or as a user lacking
     required permissions (e.g., `brent` receiving `UserForbiddenFromCallingEntryPoint`).
3. **Login Rate Limiting ([`Login.scala:32-37`](../src/main/scala/app/workerTasks/Login.scala#L32-L37)):**
   - No tests verify `TooManyLoginAttempts` after 3 failed login attempts within 1 minute or the
     `FailedAttemptsCleanupWorker`.
4. **Password Complexity Validation
   ([`PasswordValidationUtils.scala`](../src/main/scala/app/ThalesUtils/PasswordValidationUtils.scala)):**
   - No tests verify `PasswordIsInvalid` when passwords lack uppercase, lowercase, digits, special characters, or
     minimum length.
5. **Domain Error Paths on Endpoints:**
   - `DeleteRoleById` when `RoleHasAssociatedUsers` or `NoSuchRoleId`
   - `CreateRole` / `CreateUser` duplicate constraint violations and empty parameter errors
   - `X-Request-ID` propagation and generation
   - [`UUIDGenerator`](../src/main/scala/app/uuid/UUIDGenerator.scala) unit tests
