package app.ThalesUtils

import cats.Applicative
import cats.data.{EitherT, NonEmptyVector}

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import scala.annotation.targetName
import scala.collection.View

import app.ThalesUtils.ExtensionMethodUtils.*
import org.typelevel.log4cats.Logger

object GenUtils:
  def isValidPort(port: Int): Boolean = port > 0 && port < 65536

  def logi[F[_]: Logger as logger](s: String): F[Unit] =
    logger.info(s)
  end logi

  def logi[F[_]: Logger as logger](fiber: String, s: String): F[Unit] =
    logi(s"$fiber :: $s")
  end logi

  def logi[F[_]: Logger as logger](fiber: String, uuid: String, s: String): F[Unit] =
    logi(s"$fiber [$uuid] :: $s")
  end logi

  def loge[F[_]: Logger as logger](e: Throwable, fiber: String, s: String): F[Unit] =
    logger.error(e)(s"$fiber :: $s")
  end loge

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
  def mapBoth[A, B, C, D](fFirst: A => C, fSecond: B => D, p: (A, B)): (C, D) = (fFirst(p._1), fSecond(p._2))

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

    inline private def fail[E](e: => E): EitherT[F, E, Unit] =
      EitherT(leftF(e))
    end fail

    inline def apply[E](b: Boolean, error: => E): EitherT[F, E, Unit] =
      if b then fail(error) else unitRight.asInstanceOf[EitherT[F, E, Unit]]
    end apply
  end EitherTFailIf

  // This exists because the implementation of -> in the standard Scala library
  // generates a lot more code than it should.  This implementation is truly
  // a zero cost abstraction but (currently) the Scala one is not (I filed a bug).
  extension [A](a: A) inline def ->[B](b: B): (A, B) = (a, b)

  // This trick ensures that the string constructing code is not inlined
  // inside the function calling require(), but it is moved into a separate
  // function.  Only the closure is constructed which should be in most
  // cases be a lot less code.
  private def requireLambdaExtract(errMsg: => String): Unit =
    throw IllegalArgumentException("requirement failed: " + errMsg)
  end requireLambdaExtract

  inline def require(b: Boolean, errMsg: => String): Unit =
    if (!b)
      requireLambdaExtract(errMsg)
  end require

  def findDuplicates[A](v: Vector[A]): Option[NonEmptyVector[A]] =
    val counts = v.groupMapReduce(identity)(_ => 1)(_ + _)

    NonEmptyVector.fromVector(
      counts.view.collect { case (a, count) if count > 1 => a }.toVector,
    )
  end findDuplicates

  @targetName("findDuplicatesNEV")
  def findDuplicates[A](v: NonEmptyVector[A]): Option[NonEmptyVector[A]] =
    findDuplicates(v.toVector)
  end findDuplicates

  /**
   * Builds an immutable Map from an IterableOnce, using a builder directly. This bypasses the internal match statement in Map.from(), providing a direct path
   * to map construction, especially useful for collections like View. Why this function you may ask -- one only has to look in Map.from() in the standard
   * library to understand why -- when called with a View, that function will do about 15 casts before it starts building anything.
   */
  def toMap[K, V](itOnce: IterableOnce[(K, V)]): Map[K, V] =
    val builder = Map.newBuilder[K, V]
    val sizeHint = itOnce.knownSize

    if sizeHint >= 0 then builder.sizeHint(sizeHint)

    val it = itOnce.iterator
    while (it.hasNext)
      builder.addOne(it.next())

    builder.result()
  end toMap

  def toMap[K, V](kv: (K, V)*): Map[K, V] =
    toMap(kv)
  end toMap
end GenUtils
