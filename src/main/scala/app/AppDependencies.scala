package app

import cats.effect.std.Supervisor

import app.services.{AuthService, BoRepositoryService, ExternalApiClientService, PasswordHasherService, ServerState}
import app.uuid.UUIDGenerator
import doobie.Transactor

final class AppDependencies[F[_]](
    val serverState: ServerState[F],
    val supervisor: Supervisor[F],
    val uuidGen: UUIDGenerator[F],
    val uuidScope: TraceIdScope[F, Option[String]],

    // The services
    val externalApiClientService: ExternalApiClientService[F],
    val boRepositoryService: BoRepositoryService,
    val passwordHasherService: PasswordHasherService[F],
    val authService: AuthService[F],
    val xa: Transactor[F],
)
end AppDependencies
