package io.github.j5ik2o.pcqrses.command.useCase.inventory

import io.github.j5ik2o.pcqrses.command.useCase.users.UseCaseError

sealed trait InventoryUseCaseError extends UseCaseError

object InventoryUseCaseError {
  final case class ReceiveFailed(message: String) extends InventoryUseCaseError
  final case class ReserveFailed(message: String) extends InventoryUseCaseError
  final case class ReleaseFailed(message: String) extends InventoryUseCaseError
  final case class IssueFailed(message: String) extends InventoryUseCaseError
  final case class AdjustFailed(message: String) extends InventoryUseCaseError
  final case class VersionMismatch(message: String) extends InventoryUseCaseError
  final case class UnexpectedError(message: String, cause: Option[Throwable] = None)
      extends InventoryUseCaseError
}
