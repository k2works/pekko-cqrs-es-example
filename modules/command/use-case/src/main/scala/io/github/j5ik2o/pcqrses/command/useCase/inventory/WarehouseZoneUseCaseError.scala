package io.github.j5ik2o.pcqrses.command.useCase.inventory

import io.github.j5ik2o.pcqrses.command.useCase.users.UseCaseError

sealed trait WarehouseZoneUseCaseError extends UseCaseError

object WarehouseZoneUseCaseError {
  final case class UpdateFailed(message: String) extends WarehouseZoneUseCaseError
  final case class DeactivateFailed(message: String) extends WarehouseZoneUseCaseError
  final case class ReactivateFailed(message: String) extends WarehouseZoneUseCaseError
  final case class UnexpectedError(message: String, cause: Option[Throwable] = None)
      extends WarehouseZoneUseCaseError
}
