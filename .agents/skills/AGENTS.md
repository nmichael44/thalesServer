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

## Notes
- In the `thalesProtocol` and `thalesServer` projects, you can compile the code using `sbt z` (an sbt alias).
- In the `thalesProtocol` project, you can run the tests using `sbt x` (an sbt alias).
- In the `thalesProtocol` project, after making changes that compile successfully, you must publish it so that `thalesServer` can resolve the updated API interface. This is done by running `sbt pl` (an sbt alias).
- `thalesServer` is the concrete implementation of the interface defined in `thalesProtocol`.
