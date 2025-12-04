package io.github.j5ik2o.pcqrses.command.interfaceAdapter.aggregate.inventory

import io.github.j5ik2o.pcqrses.command.domain.inventory.*

enum CustomerAggregateState {
  case NotCreated(id: CustomerId)
  case Active(customer: Customer)
  case Inactive(customer: Customer)

  def applyEvent(event: CustomerEvent): CustomerAggregateState = (this, event) match {
    case (
          NotCreated(id),
          CustomerEvent.Created_V1(_, entityId, customerCode, name, customerType, _)
        ) if id == entityId =>
      Active(Customer(entityId, customerCode, name, customerType)._1)

    case (
          Active(customer),
          CustomerEvent.Updated_V1(_, entityId, _, newName, _, newCustomerType, _)
        ) if customer.id == entityId =>
      Active(customer.update(newName, newCustomerType) match {
        case Right((updatedCustomer, _)) => updatedCustomer
        case Left(error) =>
          throw new IllegalStateException(s"Failed to update customer: $error")
      })

    case (Active(customer), CustomerEvent.Deactivated_V1(_, entityId, _))
        if customer.id == entityId =>
      Inactive(customer.deactivate match {
        case Right((deactivatedCustomer, _)) => deactivatedCustomer
        case Left(error) =>
          throw new IllegalStateException(s"Failed to deactivate customer: $error")
      })

    case (Inactive(customer), CustomerEvent.Reactivated_V1(_, entityId, _))
        if customer.id == entityId =>
      Active(customer.reactivate match {
        case Right((reactivatedCustomer, _)) => reactivatedCustomer
        case Left(error) =>
          throw new IllegalStateException(s"Failed to reactivate customer: $error")
      })

    case (NotCreated(id), _) =>
      throw new IllegalStateException(s"Cannot apply event $event to NotCreated state with id $id")

    case (Active(customer), _) =>
      throw new IllegalStateException(
        s"Cannot apply event $event to Active state with customer $customer"
      )

    case (Inactive(customer), _) =>
      throw new IllegalStateException(
        s"Cannot apply event $event to Inactive state with customer $customer"
      )
  }
}
