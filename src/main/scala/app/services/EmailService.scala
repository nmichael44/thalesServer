package app.services

import app.model.AppModel.EmailMessage

trait EmailService[F[_]]:
  def sendEmail(msg: EmailMessage): F[Long]
end EmailService
