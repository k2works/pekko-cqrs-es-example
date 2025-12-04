package io.github.j5ik2o.pcqrses.command.interfaceAdapter.aggregate.inventory

import com.github.j5ik2o.pekko.persistence.effector.scaladsl.{
  PersistenceEffector,
  PersistenceEffectorConfig,
  PersistenceMode,
  RetentionCriteria,
  SnapshotCriteria
}
import io.github.j5ik2o.pcqrses.command.domain.inventory.{
  WarehouseZone,
  WarehouseZoneEvent,
  WarehouseZoneId
}
import io.github.j5ik2o.pcqrses.command.interfaceAdapter.contract.inventory.WarehouseZoneProtocol.*
import org.apache.pekko.actor.typed.{Behavior, SupervisorStrategy}
import org.apache.pekko.actor.typed.scaladsl.Behaviors

object WarehouseZoneAggregate {

  private def handleNotCreated(
      state: WarehouseZoneAggregateState.NotCreated,
      effector: PersistenceEffector[WarehouseZoneAggregateState, WarehouseZoneEvent, Command]
  ): Behavior[Command] = Behaviors.receiveMessagePartial {
    case CreateWarehouseZone(id, warehouseId, zoneCode, name, zoneType, capacity, replyTo)
        if state.id == id =>
      val (newState, event) = WarehouseZone(id, warehouseId, zoneCode, name, zoneType, capacity)
      effector.persistEvent(event) { _ =>
        replyTo ! CreateWarehouseZoneSucceeded(id)
        handleActive(WarehouseZoneAggregateState.Active(newState), effector)
      }
    case GetWarehouseZone(id, replyTo) if state.id == id =>
      replyTo ! GetWarehouseZoneNotFoundFailed(id)
      Behaviors.same
  }

  private def handleActive(
      state: WarehouseZoneAggregateState.Active,
      effector: PersistenceEffector[WarehouseZoneAggregateState, WarehouseZoneEvent, Command]
  ): Behavior[Command] =
    Behaviors.receiveMessagePartial {
      case UpdateWarehouseZone(id, newName, newCapacity, replyTo) if state.zone.id == id =>
        state.zone.update(newName, newCapacity) match {
          case Left(reason) =>
            replyTo ! UpdateWarehouseZoneFailed(id, reason)
            Behaviors.same
          case Right((newZone, event)) =>
            effector.persistEvent(event) { _ =>
              replyTo ! UpdateWarehouseZoneSucceeded(id)
              handleActive(state.copy(zone = newZone), effector)
            }
        }
      case DeactivateWarehouseZone(id, replyTo) if state.zone.id == id =>
        state.zone.deactivate match {
          case Left(reason) =>
            replyTo ! DeactivateWarehouseZoneFailed(id, reason)
            Behaviors.same
          case Right((deactivatedZone, event)) =>
            effector.persistEvent(event) { _ =>
              replyTo ! DeactivateWarehouseZoneSucceeded(id)
              handleInactive(WarehouseZoneAggregateState.Inactive(deactivatedZone), effector)
            }
        }
      case GetWarehouseZone(id, replyTo) if state.zone.id == id =>
        replyTo ! GetWarehouseZoneSucceeded(state.zone)
        Behaviors.same
    }

  private def handleInactive(
      state: WarehouseZoneAggregateState.Inactive,
      effector: PersistenceEffector[WarehouseZoneAggregateState, WarehouseZoneEvent, Command]
  ): Behavior[Command] =
    Behaviors.receiveMessagePartial {
      case ReactivateWarehouseZone(id, replyTo) if state.zone.id == id =>
        state.zone.reactivate match {
          case Left(reason) =>
            replyTo ! ReactivateWarehouseZoneFailed(id, reason)
            Behaviors.same
          case Right((reactivatedZone, event)) =>
            effector.persistEvent(event) { _ =>
              replyTo ! ReactivateWarehouseZoneSucceeded(id)
              handleActive(WarehouseZoneAggregateState.Active(reactivatedZone), effector)
            }
        }
      case GetWarehouseZone(id, replyTo) if state.zone.id == id =>
        replyTo ! GetWarehouseZoneSucceeded(state.zone)
        Behaviors.same
    }

  def apply(id: WarehouseZoneId): Behavior[Command] = {
    val config = PersistenceEffectorConfig
      .create[WarehouseZoneAggregateState, WarehouseZoneEvent, Command](
        persistenceId = s"${id.entityTypeName}-${id.asString}",
        initialState = WarehouseZoneAggregateState.NotCreated(id),
        applyEvent = (state, event) => state.applyEvent(event)
      )
      .withPersistenceMode(PersistenceMode.Persisted)
      .withSnapshotCriteria(SnapshotCriteria.every(1000))
      .withRetentionCriteria(RetentionCriteria.snapshotEvery(2))
    Behaviors.setup[Command] { implicit ctx =>
      Behaviors
        .supervise(
          PersistenceEffector
            .fromConfig[WarehouseZoneAggregateState, WarehouseZoneEvent, Command](
              config
            ) {
              case (initialState: WarehouseZoneAggregateState.NotCreated, effector) =>
                handleNotCreated(initialState, effector)
              case (initialState: WarehouseZoneAggregateState.Active, effector) =>
                handleActive(initialState, effector)
              case (initialState: WarehouseZoneAggregateState.Inactive, effector) =>
                handleInactive(initialState, effector)
            }
        )
        .onFailure[IllegalArgumentException](
          SupervisorStrategy.restart
        )
    }
  }
}
