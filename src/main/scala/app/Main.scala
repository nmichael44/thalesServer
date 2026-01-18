package app

import cats.effect.*

import app.auth.Permissions

object Main extends IOApp:
  private val program: IO[ExitCode] =
    IO.println("Hi there dude") *> ThalesServer.run
  end program

  override def run(args: List[String]): IO[ExitCode] =
    program
  end run
end Main
