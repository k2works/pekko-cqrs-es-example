package io.github.j5ik2o.pcqrses.command.interfaceAdapter.aggregate.inventory

import io.github.j5ik2o.pcqrses.command.domain.inventory.*

enum WarehouseAggregateState {
  case NotCreated(id: WarehouseId)
  case Active(warehouse: Warehouse)
  case Inactive(warehouse: Warehouse)

  def applyEvent(event: WarehouseEvent): WarehouseAggregateState = (this, event) match {
    case (
          NotCreated(id),
          WarehouseEvent.Created_V1(_, entityId, warehouseCode, name, location, _)
        ) if id == entityId =>
      Active(Warehouse(entityId, warehouseCode, name, location)._1)

    case (Active(warehouse), WarehouseEvent.Updated_V1(_, entityId, _, newName, _, newLocation, _))
        if warehouse.id == entityId =>
      Active(warehouse.update(newName, newLocation) match {
        case Right((updatedWarehouse, _)) => updatedWarehouse
        case Left(error) =>
          throw new IllegalStateException(s"Failed to update warehouse: $error")
      })

    case (Active(warehouse), WarehouseEvent.Deactivated_V1(_, entityId, _))
        if warehouse.id == entityId =>
      Inactive(warehouse.deactivate match {
        case Right((deactivatedWarehouse, _)) => deactivatedWarehouse
        case Left(error) =>
          throw new IllegalStateException(s"Failed to deactivate warehouse: $error")
      })

    case (Inactive(warehouse), WarehouseEvent.Reactivated_V1(_, entityId, _))
        if warehouse.id == entityId =>
      Active(warehouse.reactivate match {
        case Right((reactivatedWarehouse, _)) => reactivatedWarehouse
        case Left(error) =>
          throw new IllegalStateException(s"Failed to reactivate warehouse: $error")
      })

    case (NotCreated(id), _) =>
      throw new IllegalStateException(s"Cannot apply event $event to NotCreated state with id $id")

    case (Active(warehouse), _) =>
      throw new IllegalStateException(
        s"Cannot apply event $event to Active state with warehouse $warehouse"
      )

    case (Inactive(warehouse), _) =>
      throw new IllegalStateException(
        s"Cannot apply event $event to Inactive state with warehouse $warehouse"
      )
  }
}
