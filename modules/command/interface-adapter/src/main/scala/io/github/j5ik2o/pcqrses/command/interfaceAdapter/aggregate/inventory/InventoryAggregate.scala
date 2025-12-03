package io.github.j5ik2o.pcqrses.command.interfaceAdapter.aggregate.inventory

import com.github.j5ik2o.pekko.persistence.effector.scaladsl.{
  PersistenceEffector,
  PersistenceEffectorConfig,
  PersistenceMode,
  RetentionCriteria,
  SnapshotCriteria
}
import io.github.j5ik2o.pcqrses.command.domain.inventory.{Inventory, InventoryEvent, InventoryId}
import io.github.j5ik2o.pcqrses.command.interfaceAdapter.contract.inventory.InventoryProtocol.*
import org.apache.pekko.actor.typed.{Behavior, SupervisorStrategy}
import org.apache.pekko.actor.typed.scaladsl.Behaviors

object InventoryAggregate {

  private def handleNotCreated(
      state: InventoryAggregateState.NotCreated,
      effector: PersistenceEffector[InventoryAggregateState, InventoryEvent, Command]
  ): Behavior[Command] = Behaviors.receiveMessagePartial {
    case CreateInventory(id, productId, warehouseZoneId, replyTo) if state.id == id =>
      val inventory = Inventory.create(id, productId, warehouseZoneId)
      replyTo ! CreateInventorySucceeded(id)
      handleCreated(InventoryAggregateState.Created(inventory), effector)

    case ReceiveInventory(id, quantity, expectedVersion, replyTo) if state.id == id =>
      // 初回入庫でCreateInventoryを明示的に呼ばずに在庫を作成することも可能にする
      // 実際のビジネス要件に応じて、この動作を変更することもできる
      replyTo ! ReceiveInventoryFailed(id, io.github.j5ik2o.pcqrses.command.domain.inventory.ReceiveInventoryError.VersionMismatch)
      Behaviors.same

    case GetInventory(id, replyTo) if state.id == id =>
      replyTo ! GetInventoryNotFoundFailed(id)
      Behaviors.same
  }

  private def handleCreated(
      state: InventoryAggregateState.Created,
      effector: PersistenceEffector[InventoryAggregateState, InventoryEvent, Command]
  ): Behavior[Command] =
    Behaviors.receiveMessagePartial {
      case ReceiveInventory(id, quantity, expectedVersion, replyTo) if state.inventory.id == id =>
        state.inventory.receive(quantity, expectedVersion) match {
          case Left(reason) =>
            replyTo ! ReceiveInventoryFailed(id, reason)
            Behaviors.same
          case Right((newInventory, event)) =>
            effector.persistEvent(event) { _ =>
              replyTo ! ReceiveInventorySucceeded(id, newInventory.version)
              handleCreated(state.copy(inventory = newInventory), effector)
            }
        }

      case ReserveInventory(id, quantity, expectedVersion, replyTo) if state.inventory.id == id =>
        state.inventory.reserve(quantity, expectedVersion) match {
          case Left(reason) =>
            replyTo ! ReserveInventoryFailed(id, reason)
            Behaviors.same
          case Right((newInventory, event)) =>
            effector.persistEvent(event) { _ =>
              replyTo ! ReserveInventorySucceeded(id, newInventory.version)
              handleCreated(state.copy(inventory = newInventory), effector)
            }
        }

      case ReleaseInventory(id, quantity, expectedVersion, replyTo) if state.inventory.id == id =>
        state.inventory.release(quantity, expectedVersion) match {
          case Left(reason) =>
            replyTo ! ReleaseInventoryFailed(id, reason)
            Behaviors.same
          case Right((newInventory, event)) =>
            effector.persistEvent(event) { _ =>
              replyTo ! ReleaseInventorySucceeded(id, newInventory.version)
              handleCreated(state.copy(inventory = newInventory), effector)
            }
        }

      case IssueInventory(id, quantity, expectedVersion, replyTo) if state.inventory.id == id =>
        state.inventory.issue(quantity, expectedVersion) match {
          case Left(reason) =>
            replyTo ! IssueInventoryFailed(id, reason)
            Behaviors.same
          case Right((newInventory, event)) =>
            effector.persistEvent(event) { _ =>
              replyTo ! IssueInventorySucceeded(id, newInventory.version)
              handleCreated(state.copy(inventory = newInventory), effector)
            }
        }

      case AdjustInventory(id, newQuantity, reason, expectedVersion, replyTo)
          if state.inventory.id == id =>
        state.inventory.adjust(newQuantity, reason, expectedVersion) match {
          case Left(adjustReason) =>
            replyTo ! AdjustInventoryFailed(id, adjustReason)
            Behaviors.same
          case Right((newInventory, event)) =>
            effector.persistEvent(event) { _ =>
              replyTo ! AdjustInventorySucceeded(id, newInventory.version)
              handleCreated(state.copy(inventory = newInventory), effector)
            }
        }

      case GetInventory(id, replyTo) if state.inventory.id == id =>
        replyTo ! GetInventorySucceeded(state.inventory)
        Behaviors.same
    }

  def apply(id: InventoryId): Behavior[Command] = {
    val config = PersistenceEffectorConfig
      .create[InventoryAggregateState, InventoryEvent, Command](
        persistenceId = s"${id.entityTypeName}-${id.asString}",
        initialState = InventoryAggregateState.NotCreated(id),
        applyEvent = (state, event) => state.applyEvent(event)
      )
      .withPersistenceMode(PersistenceMode.Persisted)
      .withSnapshotCriteria(SnapshotCriteria.every(1000))
      .withRetentionCriteria(RetentionCriteria.snapshotEvery(2))
    Behaviors.setup[Command] { implicit ctx =>
      Behaviors
        .supervise(
          PersistenceEffector
            .fromConfig[InventoryAggregateState, InventoryEvent, Command](
              config
            ) {
              case (initialState: InventoryAggregateState.NotCreated, effector) =>
                handleNotCreated(initialState, effector)
              case (initialState: InventoryAggregateState.Created, effector) =>
                handleCreated(initialState, effector)
            }
        )
        .onFailure[IllegalArgumentException](
          SupervisorStrategy.restart
        )
    }
  }
}
