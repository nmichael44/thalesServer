package app.entrypoints

object EntryPointUtils:
  def internalServerError[F[_], T](epErrors: EntryPointErrors[F], entryPont: String): F[T] =
    epErrors.internalServerError[T](s"$entryPont: Bad pattern match for result.")
  end internalServerError
end EntryPointUtils
