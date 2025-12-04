package io.github.j5ik2o.pcqrses.command.useCase.inventory

import io.github.j5ik2o.pcqrses.command.useCase.users.UseCaseError

sealed trait CustomerUseCaseError extends UseCaseError

object CustomerUseCaseError {
  final case class UpdateFailed(message: String) extends CustomerUseCaseError
  final case class DeactivateFailed(message: String) extends CustomerUseCaseError
  final case class ReactivateFailed(message: String) extends CustomerUseCaseError
  final case class UnexpectedError(message: String, cause: Option[Throwable] = None)
      extends CustomerUseCaseError
}
