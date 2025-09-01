package app.entrypoints

import sttp.tapir.server.ServerEndpoint

trait ThalesEntryPoint[F[_]]:
  def getEntryPoint: ServerEndpoint[Any, F]
