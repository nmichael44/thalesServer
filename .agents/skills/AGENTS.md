# Engineering Guardrails & Architecture

## Project Structure
- This project is a multi-folder workspace consisting of an API interface and a server implementation.
- **Folder A (Interface):** Contains Smithy4s `.smithy` protocol definitions. Do NOT write Scala code here manually.
- **Folder B (Implementation):** Contains the concrete Scala 3 server implementation.

## Tech Stack & Standards
- **Language:** Scala 3. Always use modern Scala 3 syntax (e.g., `given`/`using` instead of Scala 2 `implicit`, clean indentation syntax where applicable).
- **Effect System:** Pure functional programming via Cats Effect 3. All side effects, database calls, and network requests must be wrapped in `F` (tagless final style). Never use raw `try/catch` or `Future`.
- **API Generation:** We use Smithy4s. When changes to the API are required, always modify the `.smithy` files in the Interface folder first. Do not attempt to implement the generated traits until instructed.
