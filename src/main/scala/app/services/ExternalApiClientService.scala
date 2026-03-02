package app.services

trait ExternalApiClientService[F[_]]:
  def fetchUri(uri: org.http4s.Uri): F[String]
end ExternalApiClientService
