package app.ThalesUtils

import scala.concurrent.duration.FiniteDuration
import scala.jdk.DurationConverters.ScalaDurationOps

object TimeUtils:
  private def calcPart(value: Long, unit: String): String =
    if value > 0 then value.toString + " " + unit + (if value != 1 then "s" else "") else ""
  end calcPart

  private def durationToStringImpl(d: java.time.Duration): String =
    val days = calcPart(d.toDaysPart, "day")
    val hours = calcPart(d.toHoursPart, "hour")
    val minutes = calcPart(d.toMinutesPart, "minute")
    val seconds = calcPart(d.toSecondsPart, "second")

    val nanos = d.toNanosPart
    val millis = calcPart(nanos / 1_000_000, "millisecond")
    val micros = calcPart((nanos / 1_000) % 1_000, "microsecond")
    val finalNanos = calcPart(nanos % 1_000, "nanosecond")

    val res = Vector(days, hours, minutes, seconds, millis, micros, finalNanos).view
      .filter(_.nonEmpty)
      .mkString(", ")

    if res.isEmpty then "0 seconds" else res
  end durationToStringImpl

  def durationToString(d: FiniteDuration): String =
    durationToStringImpl(d.toJava)
  end durationToString

  def durationToString(d: java.time.Duration): String =
    durationToStringImpl(d)
  end durationToString
end TimeUtils
