package io.github.j5ik2o.pcqrses.command.useCase.inventory

import io.github.j5ik2o.pcqrses.command.useCase.users.UseCaseError

sealed trait ProductUseCaseError extends UseCaseError

object ProductUseCaseError {
  final case class UpdateFailed(message: String) extends ProductUseCaseError
  final case class ObsoleteFailed(message: String) extends ProductUseCaseError
  final case class UnexpectedError(message: String, cause: Option[Throwable] = None)
      extends ProductUseCaseError
}
