package io.github.j5ik2o.pcqrses.command.useCase.inventory

import io.github.j5ik2o.pcqrses.command.useCase.users.UseCaseError

sealed trait WarehouseUseCaseError extends UseCaseError

object WarehouseUseCaseError {
  final case class UpdateFailed(message: String) extends WarehouseUseCaseError
  final case class DeactivateFailed(message: String) extends WarehouseUseCaseError
  final case class ReactivateFailed(message: String) extends WarehouseUseCaseError
  final case class UnexpectedError(message: String, cause: Option[Throwable] = None)
      extends WarehouseUseCaseError
}
