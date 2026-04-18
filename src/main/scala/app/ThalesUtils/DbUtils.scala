package app.ThalesUtils

import cats.data.NonEmptyVector
import cats.implicits.*

import app.entrypoints.smithy.{HashedUserPassword, LoginName, PermissionId, PermissionName, RoleId, RoleName, UserId}
import doobie.{ConnectionIO, Fragment}
import doobie.util.Read
import doobie.util.meta.Meta

object DbUtils:
  inline private val UniqueViolation = "23505"

  def uniquenessViolated(sqlState: String): Boolean =
    sqlState == UniqueViolation
  end uniquenessViolated

  given Meta[UserId] = Meta[Long].imap(UserId.apply)(_.value)

  given Meta[LoginName] = Meta[String].imap(LoginName.apply)(_.value)

  given Meta[RoleId] = Meta[Long].imap(RoleId.apply)(_.value)

  given Meta[RoleName] = Meta[String].imap(RoleName.apply)(_.value)

  given Meta[PermissionId] = Meta[Long].imap(PermissionId.apply)(_.value)

  given Meta[HashedUserPassword] = Meta[String].imap(HashedUserPassword.apply)(_.value)

  given Meta[PermissionName] = Meta[String].imap(PermissionName.apply)(_.value)

  extension [A](obj: A)
    inline def pureCon: ConnectionIO[A] =
      doobie.FC.pure(obj)
    end pureCon

  extension [K, V: Read](sql: Fragment)
    def toIdxMap(fIdx: V => K): ConnectionIO[Map[K, V]] =
      sql
        .query[V]
        .stream
        .compile
        .fold(Map.empty[K, V])((m, e) => m.updated(fIdx(e), e))
    end toIdxMap

    def toVec[A: Read]: ConnectionIO[Vector[A]] =
      sql.query[A].to[Vector]
    end toVec

    def toOpt[A: Read]: ConnectionIO[Option[A]] =
      sql.query[A].option
    end toOpt

    def toSet[A: Read]: ConnectionIO[Set[A]] =
      sql.query[A].to[Set]
    end toSet

    def toUnique[A: Read]: ConnectionIO[A] =
      sql.query[A].unique
    end toUnique

    def exec: ConnectionIO[Int] =
      sql.update.run
    end exec

    def execToUnit: ConnectionIO[Unit] =
      sql.update.run.void
    end execToUnit
  end extension

  private type MV[K, V] = Map[K, Vector[V]]

  private def toGroupedMapImpl[K, V](v: Vector[(K, V)], allKeys: Vector[K]): MV[K, V] =
    val m = v.groupMap(_._1)(_._2)

    if m.size == allKeys.size then m
    else allKeys.view.filterNot(m.contains).foldLeft(m)((acc, e) => acc.updated(e, Vector.empty))
  end toGroupedMapImpl

  extension [K, V](v: Vector[(K, V)])
    def toGroupedMapForV(allKeys: Vector[K]): MV[K, V] =
      toGroupedMapImpl(v, allKeys)
    end toGroupedMapForV

    def toGroupedMapForNev(allKeys: NonEmptyVector[K]): MV[K, V] =
      toGroupedMapImpl(v, allKeys.toVector)
    end toGroupedMapForNev
  end extension
end DbUtils
