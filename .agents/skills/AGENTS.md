# Engineering Guardrails & Architecture

## Project Structure
- This project is a multi-folder workspace consisting of an API interface and a server implementation.
- **Folder A (Interface):** Contains Smithy4s `.smithy` protocol definitions. Do NOT write Scala code here manually.
- **Folder B (Implementation):** Contains the concrete Scala 3 server implementation.

## Tech Stack & Standards
- **Language:** Scala 3. Always use modern Scala 3 syntax (e.g., `given`/`using` instead of Scala 2 `implicit`, clean indentation syntax where applicable).
- **Effect System:** Pure functional programming via Cats Effect 3. All side effects, database calls, and network requests must be wrapped in `F` (tagless final style). Never use raw `try/catch` or `Future`.
- **API Generation:** We use Smithy4s. When changes to the API are required, always modify the `.smithy` files in the Interface folder first. Do not attempt to implement the generated traits until instructed.
- **Prefer Eta-Expansion (Point-Free Style):** Avoid wrapping function or method calls in redundant lambda parameters (e.g., `n => f(n)`) when passing them to higher-order functions (such as `map`, `flatMap`, `traverseVoid`, `filter`). Pass the function/method reference directly: prefer `traverseVoid(async.sleep)` over `traverseVoid(d => async.sleep(d))`.
- **Avoid Braces (Scala 3 Indentation):** Try to use the Scala 3 convention of avoiding braces `{}` whenever possible. We can use `:` and specify the lambda without `{}`.
- **Scala 3 Block Endings:** Always append the standard Scala 3 `end` marker to all block definitions, including classes, objects, traits, and functions/methods (`defs`):
  ```scala
  private def myFunction(x: Int): F[Unit] =
    // ...
  end myFunction
  ```
- **Implicit & Context Bound Constraints:** Do not pass implicit parameters (like `Logger[F]` or `Applicative[F]`) using explicit `using` clauses or implicit parameters in constructors/methods. Instead, always define them as named type constraints/context bounds on `F[_]` where possible:
  ```scala
  private final class MyService[F[_]: { Applicative, Logger as logger }] extends Service[F]
  ```
- **SQL Formatting & Styling:** Always write SQL keywords in **lowercase** (e.g., `select`, `insert`, `delete`, `from`, `where`, `values`, `join`, `update`, `set`). Do not write them in uppercase. This applies to queries inside Doobie `sql"""..."""` interpolators as well as direct JDBC statements.
- **No Magic Constants:** Never use hardcoded literal values (e.g., `128`, `30`, `5`) directly inside logical expressions, SQL queries, or retry logic. Always define these as well-named, descriptive, and private constants (e.g., `private val MaxEmailsPerSweep = 128`, `private val BaseBackoffSeconds = 30`) at the top of the enclosing object or class where they are highly visible and easily configurable.
- **Explicit Type Ascriptions:** Always use explicit type ascriptions for all class, trait, and object members (both public and private constants, variables, and helper definitions), for example: `private val FiberName: String = "EmailOutboxWorker"`. This prevents type inference ambiguity, improves compiler performance, and serves as self-documenting code.


## Testing Guidelines
- **Database & Transaction Control in Tests:** When performing direct JDBC database operations in test utilities or test suites:
  * Disallow auto-commit explicitly (`conn.setAutoCommit(false)`).
  * Manually commit (`conn.commit()`) upon successful execution of operations.
  * Always wrap operations in `try-catch` blocks to execute `conn.rollback()` explicitly in the event of an error.

## Workflow Rules
- **No Premature Commits:** Never run `git commit` or `git push` without asking for explicit user approval first.
- **Single-Commit PRs & GitHub Merging:**
  * **GitHub Merging:** Always use the **"Squash and Merge"** option on GitHub when merging a Pull Request. This automatically combines all commits from the feature branch into a single clean commit on `main` and avoids creating redundant "Merge pull request..." commits, keeping the Git history perfectly clean and linear.
  * **Local Squashing (Soft Reset Method):** To keep the Pull Request itself clean and reviewable with only a single commit *before* merging, squash your local commits before pushing using the **Soft Reset Method**:
    1. Reset the branch history back to `origin/main` while preserving staged modifications:
       `git reset --soft origin/main`
    2. Commit everything as one single, clean commit:
       `git commit -m "feat: Add ..."`
    3. Force-push to remote:
       `git push --force-with-lease origin <branch-name>`

## Notes
- In the `thalesProtocol` and `thalesServer` projects, you can compile the code using `sbt z` (an sbt alias).
- In the `thalesProtocol` project, you can run the tests using `sbt x` (an sbt alias).
- In the `thalesProtocol` project, after making changes that compile successfully, you must publish it so that `thalesServer` can resolve the updated API interface. This is done by running `sbt pl` (an sbt alias).
- `thalesServer` is the concrete implementation of the interface defined in `thalesProtocol`.
