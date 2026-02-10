package app.model

import cats.data.NonEmptyVector
import cats.implicits.catsSyntaxEitherId

import java.time.Instant

import smithy4s.{Refinement, RefinementProvider, Timestamp}

given RefinementProvider[JavaTimeInstant, Timestamp, Instant] =
  Refinement.drivenBy[JavaTimeInstant](
    // Constructor: Smithy Timestamp -> Java Instant
    (ts: Timestamp) => ts.toInstant.asRight,

    // Destructor: Java Instant -> Smithy Timestamp
    (i: Instant) => Timestamp.fromInstant(i),
  )

private val anyVectorRefinement = Refinement.drivenBy[NonEmptyVecSmithy](
  (list: List[Any]) => NonEmptyVector.fromVector(list.toVector).toRight("List cannot be empty"),
  (nev: NonEmptyVector[Any]) => nev.toVector.toList,
)

private type RefinementProvType[A] = RefinementProvider[NonEmptyVecSmithy, List[A], NonEmptyVector[A]]

given [A]: RefinementProvType[A] = anyVectorRefinement.asInstanceOf[RefinementProvType[A]]
