package app.ThalesUtils

import cats.{Applicative, Functor}
import cats.data.{EitherT, NonEmptyVector, OptionT, ValidatedNec}
import cats.implicits.catsSyntaxValidatedIdBinCompat0

import scala.collection.View

object ExtensionMethodUtils:
  extension [A](nev: NonEmptyVector[A])
    inline def view: View[A] =
      nev.toVector.view
    end view

    inline def mkString(start: String, sep: String, end: String): String =
      nev.toVector.mkString(start, sep, end)
    end mkString

    inline def mkString(sep: String): String =
      nev.toVector.mkString(sep)
    end mkString
  end extension

  extension (obj: Any)
    inline def safeAs[C]: Option[C] =
      obj match
        case c: C => Some(c)
        case _ => None
    end safeAs
  end extension

  extension [F[_]: Functor, A](fa: F[A])
    inline def liftE[B]: EitherT[F, B, A] =
      EitherT.liftF[F, B, A](fa)
    end liftE
  end extension

  extension [F[_], A, B](fe: F[Either[A, B]])
    inline def toEitherT: EitherT[F, A, B] =
      EitherT(fe)
    end toEitherT
  end extension

  extension [F[_]: Applicative, A, B](e: Either[A, B])
    inline def toEitherT: EitherT[F, A, B] =
      EitherT.fromEither(e)
    end toEitherT
  end extension

  extension [F[_]: Functor, A](o: F[Option[A]])
    inline def toEitherT[B](ifNone: => B): EitherT[F, B, A] =
      EitherT.fromOptionF(o, ifNone)
    end toEitherT
  end extension

  extension (t: Boolean)
    inline def valid[A, B](a: => A, b: => B): ValidatedNec[B, A] =
      if t then a.validNec else b.invalidNec
    end valid
  end extension

  extension [A, B](p: (A, B))
    inline def mapFirst[C](f: A => C): (C, B) =
      (f(p._1), p._2)
    end mapFirst

    inline def mapSecond[C](f: B => C): (A, C) =
      (p._1, f(p._2))
    end mapSecond
  end extension

  extension [A](a: A) inline def ignore: Unit = ()

  extension (duration: java.time.Duration)
    def toReadableString: String =
      if duration.isZero then "0s"
      else
        val days = duration.toDaysPart
        val hours = duration.toHoursPart
        val minutes = duration.toMinutesPart
        val seconds = duration.toSecondsPart

        View(
          Option.when(days > 0)(s"${days}d"),
          Option.when(hours > 0)(s"${hours}h"),
          Option.when(minutes > 0)(s"${minutes}m"),
          Option.when(seconds > 0)(s"${seconds}s"),
        ).flatten.mkString(" ")
  end extension
end ExtensionMethodUtils
