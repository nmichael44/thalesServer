package app

import cats.effect.*

object Main extends IOApp:
  private val program: IO[ExitCode] =
    IO.println("Hi there") *> ThalesServer.run

  override def run(args: List[String]): IO[ExitCode] = program
