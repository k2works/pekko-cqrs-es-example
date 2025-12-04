package io.github.j5ik2o.pcqrses.command.interfaceAdapter.aggregate.inventory

import com.github.j5ik2o.pekko.persistence.effector.scaladsl.{
  PersistenceEffector,
  PersistenceEffectorConfig,
  PersistenceMode,
  RetentionCriteria,
  SnapshotCriteria
}
import io.github.j5ik2o.pcqrses.command.domain.inventory.{Warehouse, WarehouseEvent, WarehouseId}
import io.github.j5ik2o.pcqrses.command.interfaceAdapter.contract.inventory.WarehouseProtocol.*
import org.apache.pekko.actor.typed.{Behavior, SupervisorStrategy}
import org.apache.pekko.actor.typed.scaladsl.Behaviors

object WarehouseAggregate {

  private def handleNotCreated(
      state: WarehouseAggregateState.NotCreated,
      effector: PersistenceEffector[WarehouseAggregateState, WarehouseEvent, Command]
  ): Behavior[Command] = Behaviors.receiveMessagePartial {
    case CreateWarehouse(id, warehouseCode, name, location, replyTo) if state.id == id =>
      val (newState, event) = Warehouse(id, warehouseCode, name, location)
      effector.persistEvent(event) { _ =>
        replyTo ! CreateWarehouseSucceeded(id)
        handleActive(WarehouseAggregateState.Active(newState), effector)
      }
    case GetWarehouse(id, replyTo) if state.id == id =>
      replyTo ! GetWarehouseNotFoundFailed(id)
      Behaviors.same
  }

  private def handleActive(
      state: WarehouseAggregateState.Active,
      effector: PersistenceEffector[WarehouseAggregateState, WarehouseEvent, Command]
  ): Behavior[Command] =
    Behaviors.receiveMessagePartial {
      case UpdateWarehouse(id, newName, newLocation, replyTo) if state.warehouse.id == id =>
        state.warehouse.update(newName, newLocation) match {
          case Left(reason) =>
            replyTo ! UpdateWarehouseFailed(id, reason)
            Behaviors.same
          case Right((newWarehouse, event)) =>
            effector.persistEvent(event) { _ =>
              replyTo ! UpdateWarehouseSucceeded(id)
              handleActive(state.copy(warehouse = newWarehouse), effector)
            }
        }
      case DeactivateWarehouse(id, replyTo) if state.warehouse.id == id =>
        state.warehouse.deactivate match {
          case Left(reason) =>
            replyTo ! DeactivateWarehouseFailed(id, reason)
            Behaviors.same
          case Right((deactivatedWarehouse, event)) =>
            effector.persistEvent(event) { _ =>
              replyTo ! DeactivateWarehouseSucceeded(id)
              handleInactive(WarehouseAggregateState.Inactive(deactivatedWarehouse), effector)
            }
        }
      case GetWarehouse(id, replyTo) if state.warehouse.id == id =>
        replyTo ! GetWarehouseSucceeded(state.warehouse)
        Behaviors.same
    }

  private def handleInactive(
      state: WarehouseAggregateState.Inactive,
      effector: PersistenceEffector[WarehouseAggregateState, WarehouseEvent, Command]
  ): Behavior[Command] =
    Behaviors.receiveMessagePartial {
      case ReactivateWarehouse(id, replyTo) if state.warehouse.id == id =>
        state.warehouse.reactivate match {
          case Left(reason) =>
            replyTo ! ReactivateWarehouseFailed(id, reason)
            Behaviors.same
          case Right((reactivatedWarehouse, event)) =>
            effector.persistEvent(event) { _ =>
              replyTo ! ReactivateWarehouseSucceeded(id)
              handleActive(WarehouseAggregateState.Active(reactivatedWarehouse), effector)
            }
        }
      case GetWarehouse(id, replyTo) if state.warehouse.id == id =>
        replyTo ! GetWarehouseSucceeded(state.warehouse)
        Behaviors.same
    }

  def apply(id: WarehouseId): Behavior[Command] = {
    val config = PersistenceEffectorConfig
      .create[WarehouseAggregateState, WarehouseEvent, Command](
        persistenceId = s"${id.entityTypeName}-${id.asString}",
        initialState = WarehouseAggregateState.NotCreated(id),
        applyEvent = (state, event) => state.applyEvent(event)
      )
      .withPersistenceMode(PersistenceMode.Persisted)
      .withSnapshotCriteria(SnapshotCriteria.every(1000))
      .withRetentionCriteria(RetentionCriteria.snapshotEvery(2))
    Behaviors.setup[Command] { implicit ctx =>
      Behaviors
        .supervise(
          PersistenceEffector
            .fromConfig[WarehouseAggregateState, WarehouseEvent, Command](
              config
            ) {
              case (initialState: WarehouseAggregateState.NotCreated, effector) =>
                handleNotCreated(initialState, effector)
              case (initialState: WarehouseAggregateState.Active, effector) =>
                handleActive(initialState, effector)
              case (initialState: WarehouseAggregateState.Inactive, effector) =>
                handleInactive(initialState, effector)
            }
        )
        .onFailure[IllegalArgumentException](
          SupervisorStrategy.restart
        )
    }
  }
}
