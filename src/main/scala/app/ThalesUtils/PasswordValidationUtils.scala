package app.ThalesUtils

import cats.data.{NonEmptyVector, Validated, ValidatedNec}
import cats.implicits.*

import app.ThalesUtils.ExtensionMethodUtils.*

object PasswordValidationUtils:
  inline private final val PasswordMinLen = 8

  private def hasCharWithProperty(pred: Char => Boolean, password: String): Boolean =
    password.exists(pred)
  end hasCharWithProperty

  private def isLongEnough(password: String): Boolean =
    password.length >= PasswordMinLen
  end isLongEnough

  private def hasUpperCase(password: String): Boolean =
    hasCharWithProperty(_.isUpper, password)
  end hasUpperCase

  private def hasLowerCase(password: String): Boolean =
    hasCharWithProperty(_.isLower, password)
  end hasLowerCase

  private def hasDigit(password: String): Boolean =
    hasCharWithProperty(_.isDigit, password)
  end hasDigit

  private def hasSpecialChar(password: String): Boolean =
    hasCharWithProperty(c => !c.isLetterOrDigit, password)
  end hasSpecialChar

  private val ErrorStringForValidateLength: String =
    s"Password must be at least $PasswordMinLen characters."

  private def validateLength(password: String): ValidatedNec[String, Unit] =
    isLongEnough(password).valid((), ErrorStringForValidateLength)
  end validateLength

  private def validateUpperCase(password: String): ValidatedNec[String, Unit] =
    hasUpperCase(password).valid((), "Password must have uppercase characters.")
  end validateUpperCase

  private def validateLowerCase(password: String): ValidatedNec[String, Unit] =
    hasLowerCase(password).valid((), "Password must have lowercase characters.")
  end validateLowerCase

  private def validateDigit(password: String): ValidatedNec[String, Unit] =
    hasDigit(password).valid((), "Password must have at least one digit.")
  end validateDigit

  private def validateSpecialChar(password: String): ValidatedNec[String, Unit] =
    hasSpecialChar(password).valid((), "Password must have at least one special character.")
  end validateSpecialChar

  def isPasswordGoodEnough(password: String): Validated[NonEmptyVector[String], Unit] =
    (
      validateLength(password),
      validateUpperCase(password),
      validateLowerCase(password),
      validateDigit(password),
      validateSpecialChar(password),
    ).mapN(GenUtils.const5(())).leftMap(_.toNonEmptyVector)
  end isPasswordGoodEnough
end PasswordValidationUtils
