package app

import app.services.{AuthService, ClockService, EmailService, ExternalApiClientService, PasswordHasherService, RepositoryService, ServerState}
import app.uuid.UUIDGenerator
import doobie.Transactor

final class AppDependencies[F[_]](
    val serverState: ServerState[F],
    val uuidGen: UUIDGenerator[F],
    val uuidScope: TraceIdScope[F, Option[String]],

    // The services
    val emailService: EmailService[F],
    val externalApiClientService: ExternalApiClientService[F],
    val repositoryService: RepositoryService,
    val passwordHasherService: PasswordHasherService[F],
    val authService: AuthService[F],
    val clockService: ClockService[F],
    val xa: Transactor[F],
)
end AppDependencies
