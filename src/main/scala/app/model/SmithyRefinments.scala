package app.model

import cats.data.NonEmptyVector
import cats.implicits.catsSyntaxEitherId

import java.time.Instant

import doobie.Meta
import doobie.implicits.javatimedrivernative.*
import smithy4s.{Refinement, RefinementProvider, Timestamp}

given javaTimeRefProvider: RefinementProvider[JavaTimeInstant, Timestamp, Instant] =
  Refinement.drivenBy[JavaTimeInstant](
    // Constructor: Smithy Timestamp -> Java Instant
    (ts: Timestamp) => ts.toInstant.asRight,

    // Destructor: Java Instant -> Smithy Timestamp
    (i: Instant) => Timestamp.fromInstant(i),
  )
end javaTimeRefProvider

given Meta[JavaInstant] = Meta[Instant].imap(JavaInstant.apply)(_.value)

private val anyVectorRefinement = Refinement.drivenBy[NonEmptyVecSmithy](
  // Constructor: List[Any] => Either[String, NonEmptyVector[Any]]
  (list: List[Any]) => NonEmptyVector.fromVector(list.toVector).toRight("List cannot be empty"),

  // Destructor: NonEmptyVector[Any] => List[Any]
  (nev: NonEmptyVector[Any]) => nev.toVector.toList,
)

// The Type-Safe Interface
private type RefinementProvType[A] = RefinementProvider[NonEmptyVecSmithy, List[A], NonEmptyVector[A]]

given nonEmptyVectorRefProvider[A]: RefinementProvType[A] = anyVectorRefinement.asInstanceOf[RefinementProvType[A]]
