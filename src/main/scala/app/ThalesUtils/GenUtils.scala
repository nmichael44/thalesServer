package app.ThalesUtils

import cats.Applicative
import cats.data.{EitherT, NonEmptyVector}

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

import app.ThalesUtils.ExtensionMethodUtils.*
import org.typelevel.log4cats.Logger

object GenUtils:
  def isValidPort(port: Int): Boolean = port > 0 && port < 65536

  def logi[F[_]: Logger as logger](fiber: String, s: String): F[Unit] =
    logger.info(s"$fiber :: $s")
  end logi

  def loge[F[_]: Logger as logger](e: Throwable, fiber: String, s: String): F[Unit] =
    logger.error(e)(s"$fiber :: $s")
  end loge

  def logi[F[_]: Logger as logger](fiber: String, uuid: String, s: String): F[Unit] =
    logger.info(s"$fiber [$uuid] :: $s")
  end logi

  def loge[F[_]: Logger as logger](e: Throwable, fiber: String, uuid: String, s: String): F[Unit] =
    logger.error(e)(s"$fiber [$uuid] :: $s")
  end loge

  def const1[R, A](r: R): A => R = _ => r
  def const2[R, A, B](r: R): (A, B) => R = (_, _) => r
  def const3[R, A, B, C](r: R): (A, B, C) => R = (_, _, _) => r
  def const4[R, A, B, C, D](r: R): (A, B, C, D) => R = (_, _, _, _) => r
  def const5[R, A, B, C, D, E](r: R): (A, B, C, D, E) => R = (_, _, _, _, _) => r
  def const6[R, A, B, C, D, E, F](r: R): (A, B, C, D, E, F) => R = (_, _, _, _, _, _) => r
  def const7[R, A, B, C, D, E, F, G](r: R): (A, B, C, D, E, F, G) => R = (_, _, _, _, _, _, _) => r
  def const8[R, A, B, C, D, E, F, G, H](r: R): (A, B, C, D, E, F, G, H) => R = (_, _, _, _, _, _, _, _) => r
  def const9[R, A, B, C, D, E, F, G, H, I](r: R): (A, B, C, D, E, F, G, H, I) => R = (_, _, _, _, _, _, _, _, _) => r
  def const10[R, A, B, C, D, E, F, G, H, I, J](r: R): (A, B, C, D, E, F, G, H, I, J) => R = (_, _, _, _, _, _, _, _, _, _) => r

  def leftF[F[_]: Applicative as app, L, R](x: L): F[Either[L, R]] = app.pure(Left(x))
  def rightF[F[_]: Applicative as app, L, R](x: R): F[Either[L, R]] = app.pure(Right(x))

  def mapFirst[A, B, C](f: A => C)(p: (A, B)): (C, B) = (f(p._1), p._2)
  def mapSecond[A, B, C](f: B => C)(p: (A, B)): (A, C) = (p._1, f(p._2))
  def mapToFirst[B, A](f: A => B)(a: A): (B, A) = (f(a), a)
  def mapToSecond[B, A](f: B => A)(b: B): (B, A) = (b, f(b))

  def paramsToStr(params: NonEmptyVector[(String, String)]): String =
    params.view.map((param, error) => s"($param: \"$error\")").mkString("[", ", ", "]")
  end paramsToStr

  private val UrlEncoder: Base64.Encoder = Base64.getUrlEncoder.withoutPadding

  private def hashByteArray(bytes: Array[Byte]): Array[Byte] =
    MessageDigest.getInstance("SHA-256").digest(bytes)
  end hashByteArray

  def hashStringUrlEncoded(token: String): String =
    UrlEncoder.encodeToString(hashByteArray(token.getBytes(StandardCharsets.UTF_8)))
  end hashStringUrlEncoded

  def getSystemProp(s: String): Option[String] =
    Option(System.getProperty(s))
  end getSystemProp

  final class EitherTFailIf[F[_]: Applicative as app]:
    private val unitRight: EitherT[F, Nothing, Unit] = EitherT(rightF(()))

    private def fail[E](e: => E): EitherT[F, E, Unit] =
      EitherT(leftF(e))
    end fail

    inline def apply[E](b: Boolean, error: => E): EitherT[F, E, Unit] =
      if b then fail(error) else unitRight.asInstanceOf[EitherT[F, E, Unit]]
    end apply
  end EitherTFailIf

  extension [A](a: A) inline def -->[B](b: B): (A, B) = (a, b)
  end extension
end GenUtils
