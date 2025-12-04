package io.github.j5ik2o.pcqrses.command.interfaceAdapter.aggregate.inventory

import com.github.j5ik2o.pekko.persistence.effector.scaladsl.{
  PersistenceEffector,
  PersistenceEffectorConfig,
  PersistenceMode,
  RetentionCriteria,
  SnapshotCriteria
}
import io.github.j5ik2o.pcqrses.command.domain.inventory.{Customer, CustomerEvent, CustomerId}
import io.github.j5ik2o.pcqrses.command.interfaceAdapter.contract.inventory.CustomerProtocol.*
import org.apache.pekko.actor.typed.{Behavior, SupervisorStrategy}
import org.apache.pekko.actor.typed.scaladsl.Behaviors

object CustomerAggregate {

  private def handleNotCreated(
      state: CustomerAggregateState.NotCreated,
      effector: PersistenceEffector[CustomerAggregateState, CustomerEvent, Command]
  ): Behavior[Command] = Behaviors.receiveMessagePartial {
    case CreateCustomer(id, customerCode, name, customerType, replyTo) if state.id == id =>
      val (newState, event) = Customer(id, customerCode, name, customerType)
      effector.persistEvent(event) { _ =>
        replyTo ! CreateCustomerSucceeded(id)
        handleActive(CustomerAggregateState.Active(newState), effector)
      }
    case GetCustomer(id, replyTo) if state.id == id =>
      replyTo ! GetCustomerNotFoundFailed(id)
      Behaviors.same
  }

  private def handleActive(
      state: CustomerAggregateState.Active,
      effector: PersistenceEffector[CustomerAggregateState, CustomerEvent, Command]
  ): Behavior[Command] =
    Behaviors.receiveMessagePartial {
      case UpdateCustomer(id, newName, newCustomerType, replyTo) if state.customer.id == id =>
        state.customer.update(newName, newCustomerType) match {
          case Left(reason) =>
            replyTo ! UpdateCustomerFailed(id, reason)
            Behaviors.same
          case Right((newCustomer, event)) =>
            effector.persistEvent(event) { _ =>
              replyTo ! UpdateCustomerSucceeded(id)
              handleActive(state.copy(customer = newCustomer), effector)
            }
        }
      case DeactivateCustomer(id, replyTo) if state.customer.id == id =>
        state.customer.deactivate match {
          case Left(reason) =>
            replyTo ! DeactivateCustomerFailed(id, reason)
            Behaviors.same
          case Right((deactivatedCustomer, event)) =>
            effector.persistEvent(event) { _ =>
              replyTo ! DeactivateCustomerSucceeded(id)
              handleInactive(CustomerAggregateState.Inactive(deactivatedCustomer), effector)
            }
        }
      case GetCustomer(id, replyTo) if state.customer.id == id =>
        replyTo ! GetCustomerSucceeded(state.customer)
        Behaviors.same
    }

  private def handleInactive(
      state: CustomerAggregateState.Inactive,
      effector: PersistenceEffector[CustomerAggregateState, CustomerEvent, Command]
  ): Behavior[Command] =
    Behaviors.receiveMessagePartial {
      case ReactivateCustomer(id, replyTo) if state.customer.id == id =>
        state.customer.reactivate match {
          case Left(reason) =>
            replyTo ! ReactivateCustomerFailed(id, reason)
            Behaviors.same
          case Right((reactivatedCustomer, event)) =>
            effector.persistEvent(event) { _ =>
              replyTo ! ReactivateCustomerSucceeded(id)
              handleActive(CustomerAggregateState.Active(reactivatedCustomer), effector)
            }
        }
      case GetCustomer(id, replyTo) if state.customer.id == id =>
        replyTo ! GetCustomerSucceeded(state.customer)
        Behaviors.same
    }

  def apply(id: CustomerId): Behavior[Command] = {
    val config = PersistenceEffectorConfig
      .create[CustomerAggregateState, CustomerEvent, Command](
        persistenceId = s"${id.entityTypeName}-${id.asString}",
        initialState = CustomerAggregateState.NotCreated(id),
        applyEvent = (state, event) => state.applyEvent(event)
      )
      .withPersistenceMode(PersistenceMode.Persisted)
      .withSnapshotCriteria(SnapshotCriteria.every(1000))
      .withRetentionCriteria(RetentionCriteria.snapshotEvery(2))
    Behaviors.setup[Command] { implicit ctx =>
      Behaviors
        .supervise(
          PersistenceEffector
            .fromConfig[CustomerAggregateState, CustomerEvent, Command](
              config
            ) {
              case (initialState: CustomerAggregateState.NotCreated, effector) =>
                handleNotCreated(initialState, effector)
              case (initialState: CustomerAggregateState.Active, effector) =>
                handleActive(initialState, effector)
              case (initialState: CustomerAggregateState.Inactive, effector) =>
                handleInactive(initialState, effector)
            }
        )
        .onFailure[IllegalArgumentException](
          SupervisorStrategy.restart
        )
    }
  }
}
