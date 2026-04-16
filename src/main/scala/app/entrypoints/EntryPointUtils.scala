package app.entrypoints

object EntryPointUtils:
  def invalidResultType[F[_], T](epErrors: EntryPointErrors[F], entryPont: String): F[T] =
    epErrors.internalServerError[T](s"$entryPont: Invalid result type returned.")
  end invalidResultType
end EntryPointUtils
