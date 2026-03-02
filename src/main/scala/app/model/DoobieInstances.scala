package app.model

import java.time.Instant

import doobie.Meta
import doobie.implicits.javatimedrivernative.*

given Meta[JavaInstant] = Meta[Instant].imap(JavaInstant.apply)(_.value)
