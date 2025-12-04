package io.github.j5ik2o.pcqrses.command.useCase.inventory

import io.github.j5ik2o.pcqrses.command.domain.inventory.{
  StorageCondition,
  WarehouseId,
  WarehouseZoneId,
  ZoneCapacity,
  ZoneCode,
  ZoneName
}
import io.github.j5ik2o.pcqrses.command.interfaceAdapter.contract.inventory.WarehouseZoneProtocol
import io.github.j5ik2o.pcqrses.infrastructure.effect.PekkoInterop
import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
import org.apache.pekko.actor.typed.{ActorRef, Scheduler}
import org.apache.pekko.util.Timeout
import org.slf4j.LoggerFactory
import zio.{IO, Task, ZIO}

import scala.concurrent.ExecutionContext

/**
  * WarehouseZoneUseCaseの実装クラス
  *
  * Pekkoアクターを使用して倉庫ゾーン管理のビジネスロジックを実行する
  */
private[inventory] final class WarehouseZoneUseCaseImpl(
    warehouseZoneAggregateRef: ActorRef[WarehouseZoneProtocol.Command]
)(implicit
    timeout: Timeout,
    scheduler: Scheduler,
    ec: ExecutionContext
) extends WarehouseZoneUseCase {

  private val logger = LoggerFactory.getLogger(this.getClass)

  override def createWarehouseZone(
      warehouseId: WarehouseId,
      zoneCode: ZoneCode,
      name: ZoneName,
      zoneType: StorageCondition,
      capacity: ZoneCapacity
  ): IO[WarehouseZoneUseCaseError, WarehouseZoneId] =
    for {
      _ <- ZIO.succeed(
        logger.info(
          s"Creating WarehouseZone with zoneCode: ${zoneCode.value}, warehouseId: ${warehouseId.asString}"
        )
      )
      warehouseZoneId <- ZIO.succeed(WarehouseZoneId.generate())
      reply <- askActor[WarehouseZoneProtocol.CreateWarehouseZoneReply] { replyTo =>
        WarehouseZoneProtocol.CreateWarehouseZone(
          id = warehouseZoneId,
          warehouseId = warehouseId,
          zoneCode = zoneCode,
          name = name,
          zoneType = zoneType,
          capacity = capacity,
          replyTo = replyTo
        )
      }.mapError(e =>
        WarehouseZoneUseCaseError.UnexpectedError(
          s"Failed to communicate with actor: ${e.getMessage}",
          Some(e)
        )
      )
      result <- reply match {
        case WarehouseZoneProtocol.CreateWarehouseZoneSucceeded(id) =>
          ZIO.succeed(logger.info(s"WarehouseZone creation succeeded for ID: ${id.asString}")) *>
            ZIO.succeed(id)
      }
    } yield result

  override def updateWarehouseZone(
      warehouseZoneId: WarehouseZoneId,
      newName: ZoneName,
      newCapacity: ZoneCapacity
  ): IO[WarehouseZoneUseCaseError, WarehouseZoneId] =
    for {
      _ <- ZIO.succeed(
        logger.info(s"Updating WarehouseZone with ID: ${warehouseZoneId.asString}")
      )
      reply <- askActor[WarehouseZoneProtocol.UpdateWarehouseZoneReply] { replyTo =>
        WarehouseZoneProtocol.UpdateWarehouseZone(
          id = warehouseZoneId,
          newName = newName,
          newCapacity = newCapacity,
          replyTo = replyTo
        )
      }.mapError(e =>
        WarehouseZoneUseCaseError.UnexpectedError(
          s"Failed to communicate with actor: ${e.getMessage}",
          Some(e)
        )
      )
      result <- reply match {
        case WarehouseZoneProtocol.UpdateWarehouseZoneSucceeded(id) =>
          ZIO.succeed(logger.info(s"WarehouseZone update succeeded for ID: ${id.asString}")) *>
            ZIO.succeed(id)
        case WarehouseZoneProtocol.UpdateWarehouseZoneFailed(id, reason) =>
          ZIO.fail(
            WarehouseZoneUseCaseError.UpdateFailed(s"Update failed for ID ${id.asString}: $reason")
          )
      }
    } yield result

  override def deactivateWarehouseZone(
      warehouseZoneId: WarehouseZoneId
  ): IO[WarehouseZoneUseCaseError, WarehouseZoneId] =
    for {
      _ <- ZIO.succeed(
        logger.info(s"Deactivating WarehouseZone with ID: ${warehouseZoneId.asString}")
      )
      reply <- askActor[WarehouseZoneProtocol.DeactivateWarehouseZoneReply] { replyTo =>
        WarehouseZoneProtocol.DeactivateWarehouseZone(
          id = warehouseZoneId,
          replyTo = replyTo
        )
      }.mapError(e =>
        WarehouseZoneUseCaseError.UnexpectedError(
          s"Failed to communicate with actor: ${e.getMessage}",
          Some(e)
        )
      )
      result <- reply match {
        case WarehouseZoneProtocol.DeactivateWarehouseZoneSucceeded(id) =>
          ZIO.succeed(logger.info(s"WarehouseZone deactivate succeeded for ID: ${id.asString}")) *>
            ZIO.succeed(id)
        case WarehouseZoneProtocol.DeactivateWarehouseZoneFailed(id, reason) =>
          ZIO.fail(
            WarehouseZoneUseCaseError.DeactivateFailed(
              s"Deactivate failed for ID ${id.asString}: $reason"
            )
          )
      }
    } yield result

  override def reactivateWarehouseZone(
      warehouseZoneId: WarehouseZoneId
  ): IO[WarehouseZoneUseCaseError, WarehouseZoneId] =
    for {
      _ <- ZIO.succeed(
        logger.info(s"Reactivating WarehouseZone with ID: ${warehouseZoneId.asString}")
      )
      reply <- askActor[WarehouseZoneProtocol.ReactivateWarehouseZoneReply] { replyTo =>
        WarehouseZoneProtocol.ReactivateWarehouseZone(
          id = warehouseZoneId,
          replyTo = replyTo
        )
      }.mapError(e =>
        WarehouseZoneUseCaseError.UnexpectedError(
          s"Failed to communicate with actor: ${e.getMessage}",
          Some(e)
        )
      )
      result <- reply match {
        case WarehouseZoneProtocol.ReactivateWarehouseZoneSucceeded(id) =>
          ZIO.succeed(logger.info(s"WarehouseZone reactivate succeeded for ID: ${id.asString}")) *>
            ZIO.succeed(id)
        case WarehouseZoneProtocol.ReactivateWarehouseZoneFailed(id, reason) =>
          ZIO.fail(
            WarehouseZoneUseCaseError.ReactivateFailed(
              s"Reactivate failed for ID ${id.asString}: $reason"
            )
          )
      }
    } yield result

  private def askActor[R](
      createMessage: ActorRef[R] => WarehouseZoneProtocol.Command
  ): Task[R] =
    PekkoInterop.fromFuture {
      warehouseZoneAggregateRef.ask(createMessage)
    }
}
