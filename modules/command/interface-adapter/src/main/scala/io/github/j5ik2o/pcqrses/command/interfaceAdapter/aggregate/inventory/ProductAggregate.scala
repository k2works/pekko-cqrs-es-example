package io.github.j5ik2o.pcqrses.command.interfaceAdapter.aggregate.inventory

import com.github.j5ik2o.pekko.persistence.effector.scaladsl.{
  PersistenceEffector,
  PersistenceEffectorConfig,
  PersistenceMode,
  RetentionCriteria,
  SnapshotCriteria
}
import io.github.j5ik2o.pcqrses.command.domain.inventory.{Product, ProductEvent, ProductId}
import io.github.j5ik2o.pcqrses.command.interfaceAdapter.contract.inventory.ProductProtocol.*
import org.apache.pekko.actor.typed.{Behavior, SupervisorStrategy}
import org.apache.pekko.actor.typed.scaladsl.Behaviors

object ProductAggregate {

  private def handleNotCreated(
      state: ProductAggregateState.NotCreated,
      effector: PersistenceEffector[ProductAggregateState, ProductEvent, Command]
  ): Behavior[Command] = Behaviors.receiveMessagePartial {
    case CreateProduct(id, productCode, name, categoryCode, storageCondition, replyTo) if state.id == id =>
      val (newState, event) = Product(id, productCode, name, categoryCode, storageCondition)
      effector.persistEvent(event) { _ =>
        replyTo ! CreateProductSucceeded(id)
        handleCreated(ProductAggregateState.Created(newState), effector)
      }
    case GetProduct(id, replyTo) if state.id == id =>
      replyTo ! GetProductNotFoundFailed(id)
      Behaviors.same
  }

  private def handleCreated(
      state: ProductAggregateState.Created,
      effector: PersistenceEffector[ProductAggregateState, ProductEvent, Command]
  ): Behavior[Command] =
    Behaviors.receiveMessagePartial {
      case UpdateProduct(id, newName, newCategoryCode, newStorageCondition, replyTo)
          if state.product.id == id =>
        state.product.update(newName, newCategoryCode, newStorageCondition) match {
          case Left(reason) =>
            replyTo ! UpdateProductFailed(id, reason)
            Behaviors.same
          case Right((newProduct, event)) =>
            effector.persistEvent(event) { _ =>
              replyTo ! UpdateProductSucceeded(id)
              handleCreated(state.copy(product = newProduct), effector)
            }
        }
      case ObsoleteProduct(id, replyTo) if state.product.id == id =>
        state.product.obsolete match {
          case Left(reason) =>
            replyTo ! ObsoleteProductFailed(id, reason)
            Behaviors.same
          case Right((obsoletedProduct, event)) =>
            effector.persistEvent(event) { _ =>
              replyTo ! ObsoleteProductSucceeded(id)
              handleObsoleted(ProductAggregateState.Obsoleted(obsoletedProduct), effector)
            }
        }
      case GetProduct(id, replyTo) if state.product.id == id =>
        replyTo ! GetProductSucceeded(state.product)
        Behaviors.same
    }

  private def handleObsoleted(
      state: ProductAggregateState.Obsoleted,
      effector: PersistenceEffector[ProductAggregateState, ProductEvent, Command]
  ): Behavior[Command] = Behaviors.receiveMessagePartial { case GetProduct(id, replyTo) =>
    replyTo ! GetProductNotFoundFailed(id)
    Behaviors.same
  }

  def apply(id: ProductId): Behavior[Command] = {
    val config = PersistenceEffectorConfig
      .create[ProductAggregateState, ProductEvent, Command](
        persistenceId = s"${id.entityTypeName}-${id.asString}",
        initialState = ProductAggregateState.NotCreated(id),
        applyEvent = (state, event) => state.applyEvent(event)
      )
      .withPersistenceMode(PersistenceMode.Persisted)
      .withSnapshotCriteria(SnapshotCriteria.every(1000))
      .withRetentionCriteria(RetentionCriteria.snapshotEvery(2))
    Behaviors.setup[Command] { implicit ctx =>
      Behaviors
        .supervise(
          PersistenceEffector
            .fromConfig[ProductAggregateState, ProductEvent, Command](
              config
            ) {
              case (initialState: ProductAggregateState.NotCreated, effector) =>
                handleNotCreated(initialState, effector)
              case (initialState: ProductAggregateState.Created, effector) =>
                handleCreated(initialState, effector)
              case (initialState: ProductAggregateState.Obsoleted, effector) =>
                handleObsoleted(initialState, effector)
            }
        )
        .onFailure[IllegalArgumentException](
          SupervisorStrategy.restart
        )
    }
  }
}
