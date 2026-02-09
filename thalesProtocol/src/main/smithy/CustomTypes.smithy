$version: "2.0"

namespace app.model

use smithy4s.meta#refinement

// 1. Define the trait and point it to the Java class
@trait(selector: "timestamp")
@refinement(targetType: "java.time.Instant")
structure javaTimeInstant {}

@javaTimeInstant
timestamp javaInstant

@trait(selector: "list")
@refinement(
    targetType: "cats.data.NonEmptyVector"
    parameterised: true
    providerImport: "app.model.given"
)
structure nonEmptyVecSmithy {}
