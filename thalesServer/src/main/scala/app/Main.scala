package app

import cats.effect.*

object Main extends IOApp:
  private val program: IO[ExitCode] =
    IO.println("Hi, I am Thales.  Do you know thyself?") *> ThalesServer.run
  end program

  override def run(args: List[String]): IO[ExitCode] =
    program
  end run
end Main
