package app.model

import java.time.Instant

import smithy4s.{Refinement, RefinementProvider, Timestamp}

given RefinementProvider[JavaTimeInstant, Timestamp, Instant] =
  Refinement.drivenBy[JavaTimeInstant](
    // 1. Smithy Timestamp -> Java Instant
    (ts: Timestamp) => Right(ts.toInstant),

    // 2. Java Instant -> Smithy Timestamp
    (i: Instant) => Timestamp.fromInstant(i),
  )
