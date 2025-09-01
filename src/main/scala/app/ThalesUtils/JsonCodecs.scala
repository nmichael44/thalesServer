package app.ThalesUtils

import sttp.tapir.Schema

object JsonCodecs:
  given [V](using Schema[V]): Schema[Map[Long, V]] =
    Schema.schemaForMap[Long, V](_.toString)
