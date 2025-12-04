package io.github.j5ik2o.pcqrses.command.interfaceAdapter.aggregate.inventory

import io.github.j5ik2o.pcqrses.command.domain.inventory.*

enum WarehouseZoneAggregateState {
  case NotCreated(id: WarehouseZoneId)
  case Active(zone: WarehouseZone)
  case Inactive(zone: WarehouseZone)

  def applyEvent(event: WarehouseZoneEvent): WarehouseZoneAggregateState = (this, event) match {
    case (
          NotCreated(id),
          WarehouseZoneEvent.Created_V1(
            _,
            entityId,
            warehouseId,
            zoneCode,
            name,
            zoneType,
            capacity,
            _
          )
        ) if id == entityId =>
      Active(WarehouseZone(entityId, warehouseId, zoneCode, name, zoneType, capacity)._1)

    case (
          Active(zone),
          WarehouseZoneEvent.Updated_V1(_, entityId, _, newName, _, newCapacity, _)
        ) if zone.id == entityId =>
      Active(zone.update(newName, newCapacity) match {
        case Right((updatedZone, _)) => updatedZone
        case Left(error) =>
          throw new IllegalStateException(s"Failed to update warehouse zone: $error")
      })

    case (Active(zone), WarehouseZoneEvent.Deactivated_V1(_, entityId, _))
        if zone.id == entityId =>
      Inactive(zone.deactivate match {
        case Right((deactivatedZone, _)) => deactivatedZone
        case Left(error) =>
          throw new IllegalStateException(s"Failed to deactivate warehouse zone: $error")
      })

    case (Inactive(zone), WarehouseZoneEvent.Reactivated_V1(_, entityId, _))
        if zone.id == entityId =>
      Active(zone.reactivate match {
        case Right((reactivatedZone, _)) => reactivatedZone
        case Left(error) =>
          throw new IllegalStateException(s"Failed to reactivate warehouse zone: $error")
      })

    case (NotCreated(id), _) =>
      throw new IllegalStateException(s"Cannot apply event $event to NotCreated state with id $id")

    case (Active(zone), _) =>
      throw new IllegalStateException(
        s"Cannot apply event $event to Active state with warehouse zone $zone"
      )

    case (Inactive(zone), _) =>
      throw new IllegalStateException(
        s"Cannot apply event $event to Inactive state with warehouse zone $zone"
      )
  }
}
