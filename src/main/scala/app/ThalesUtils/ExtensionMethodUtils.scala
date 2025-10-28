package app.ThalesUtils

import cats.{Applicative, Functor}
import cats.data.{EitherT, NonEmptyVector, ValidatedNec}
import cats.implicits.catsSyntaxValidatedIdBinCompat0
import cats.syntax.functor.*

import scala.collection.View

object ExtensionMethodUtils:
  extension [A](nev: NonEmptyVector[A])
    inline def view: View[A] =
      nev.toVector.view
    end view

  extension [F[_], G[_]: Functor, O](s: fs2.Stream.CompileOps[F, G, O])
    inline def theLast(using fs2.Compiler[F, G]): G[O] =
      s.last.map(_.get)
    end theLast

  extension [F[_]: Applicative as app, A](b: Boolean)
    inline def whenA(fa: F[A]): F[Unit] =
      import cats.syntax.all.*
      fa.whenA(b)(using app)
    end whenA

  extension (obj: Any)
    inline def safeAs[C]: Option[C] = obj match {
      case c: C => Some(c)
      case _ => None
    }
    end safeAs

  extension [F[_]: Functor, A](fa: F[A])
    inline def lifte[B]: EitherT[F, B, A] =
      EitherT.liftF[F, B, A](fa)
    end lifte

  extension [F[_], A, B](fe: F[Either[A, B]])
    inline def toEitherT: EitherT[F, A, B] =
      EitherT(fe)
    end toEitherT

  extension [F[_]: Applicative, A, B](e: Either[A, B])
    inline def toEitherT: EitherT[F, A, B] =
      EitherT.fromEither(e)
    end toEitherT

  extension [F[_]: Functor, A](o: F[Option[A]])
    inline def toEitherT[B](ifNone: => B): EitherT[F, B, A] =
      EitherT.fromOptionF(o, ifNone)
    end toEitherT

  extension (t: Boolean)
    inline def valid[A, B](a: => A, b: => B): ValidatedNec[B, A] =
      if t then a.validNec else b.invalidNec
    end valid

  extension [A, B](p: (A, B))
    inline def mapFirst[C](f: A => C): (C, B) =
      (f(p._1), p._2)
    end mapFirst

    inline def mapSecond[C](f: B => C): (A, C) =
      (p._1, f(p._2))
    end mapSecond
end ExtensionMethodUtils
