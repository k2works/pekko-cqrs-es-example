package io.github.j5ik2o.pcqrses.command.interfaceAdapter.aggregate.inventory

import io.github.j5ik2o.pcqrses.command.domain.basic.DateTime
import io.github.j5ik2o.pcqrses.command.domain.inventory.{
  StorageCondition,
  WarehouseId as DomainWarehouseId,
  WarehouseZoneEvent as DomainWarehouseZoneEvent,
  WarehouseZoneId as DomainWarehouseZoneId,
  ZoneCapacity,
  ZoneCode,
  ZoneName
}
import io.github.j5ik2o.pcqrses.command.domain.support.DomainEventId
import io.github.j5ik2o.pcqrses.command.interfaceAdapter.persistence.inventory.*
import org.apache.pekko.serialization.SerializerWithStringManifest

class WarehouseZoneEventSerializer extends SerializerWithStringManifest {

  override def identifier: Int = 30005

  override def manifest(o: AnyRef): String = "Envelope"

  override def toBinary(o: AnyRef): Array[Byte] = {
    val envelope: WarehouseZoneEvent_Envelope = o match {
      case DomainWarehouseZoneEvent.Created_V1(id, entityId, warehouseId, zoneCode, name, zoneType, capacity, occurredAt) =>
        val payload = WarehouseZoneEvent_Created_V1(
          eventId = id.asString,
          warehouseZoneId = entityId.asString,
          warehouseId = warehouseId.asString,
          zoneCode = zoneCode.asString,
          name = name.asString,
          zoneType = zoneType.code,
          capacity = capacity.squareMeters.toString,
          occurredAt = {
            val sn = occurredAt.toSecondsAndNanos
            Some(com.google.protobuf.timestamp.Timestamp(sn._1, sn._2))
          }
        )
        WarehouseZoneEvent_Envelope(
          warehouseZoneId = entityId.asString,
          eventTypeName = "WarehouseZoneEvent.Created",
          eventTypeVersion = "V1",
          payload = com.google.protobuf.ByteString.copyFrom(payload.toByteArray),
          occurredAt = {
            val sn = occurredAt.toSecondsAndNanos
            Some(com.google.protobuf.timestamp.Timestamp(sn._1, sn._2))
          }
        )

      case DomainWarehouseZoneEvent.Updated_V1(id, entityId, oldName, newName, oldCapacity, newCapacity, occurredAt) =>
        val payload = WarehouseZoneEvent_Updated_V1(
          eventId = id.asString,
          warehouseZoneId = entityId.asString,
          oldName = oldName.asString,
          newName = newName.asString,
          oldCapacity = oldCapacity.squareMeters.toString,
          newCapacity = newCapacity.squareMeters.toString,
          occurredAt = {
            val sn = occurredAt.toSecondsAndNanos
            Some(com.google.protobuf.timestamp.Timestamp(sn._1, sn._2))
          }
        )
        WarehouseZoneEvent_Envelope(
          warehouseZoneId = entityId.asString,
          eventTypeName = "WarehouseZoneEvent.Updated",
          eventTypeVersion = "V1",
          payload = com.google.protobuf.ByteString.copyFrom(payload.toByteArray),
          occurredAt = {
            val sn = occurredAt.toSecondsAndNanos
            Some(com.google.protobuf.timestamp.Timestamp(sn._1, sn._2))
          }
        )

      case DomainWarehouseZoneEvent.Deactivated_V1(id, entityId, occurredAt) =>
        val payload = WarehouseZoneEvent_Deactivated_V1(
          eventId = id.asString,
          warehouseZoneId = entityId.asString,
          occurredAt = {
            val sn = occurredAt.toSecondsAndNanos
            Some(com.google.protobuf.timestamp.Timestamp(sn._1, sn._2))
          }
        )
        WarehouseZoneEvent_Envelope(
          warehouseZoneId = entityId.asString,
          eventTypeName = "WarehouseZoneEvent.Deactivated",
          eventTypeVersion = "V1",
          payload = com.google.protobuf.ByteString.copyFrom(payload.toByteArray),
          occurredAt = {
            val sn = occurredAt.toSecondsAndNanos
            Some(com.google.protobuf.timestamp.Timestamp(sn._1, sn._2))
          }
        )

      case DomainWarehouseZoneEvent.Reactivated_V1(id, entityId, occurredAt) =>
        val payload = WarehouseZoneEvent_Reactivated_V1(
          eventId = id.asString,
          warehouseZoneId = entityId.asString,
          occurredAt = {
            val sn = occurredAt.toSecondsAndNanos
            Some(com.google.protobuf.timestamp.Timestamp(sn._1, sn._2))
          }
        )
        WarehouseZoneEvent_Envelope(
          warehouseZoneId = entityId.asString,
          eventTypeName = "WarehouseZoneEvent.Reactivated",
          eventTypeVersion = "V1",
          payload = com.google.protobuf.ByteString.copyFrom(payload.toByteArray),
          occurredAt = {
            val sn = occurredAt.toSecondsAndNanos
            Some(com.google.protobuf.timestamp.Timestamp(sn._1, sn._2))
          }
        )
    }
    envelope.toByteArray
  }

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef = {
    val envelope = WarehouseZoneEvent_Envelope.parseFrom(bytes)
    (envelope.eventTypeName, envelope.eventTypeVersion) match {
      case ("WarehouseZoneEvent.Created", "V1") =>
        val value = WarehouseZoneEvent_Created_V1.parseFrom(envelope.payload.toByteArray)
        DomainWarehouseZoneEvent.Created_V1(
          id = DomainEventId.from(value.eventId),
          entityId = DomainWarehouseZoneId.from(value.warehouseZoneId),
          warehouseId = DomainWarehouseId.from(value.warehouseId),
          zoneCode = ZoneCode.from(value.zoneCode),
          name = ZoneName.from(value.name),
          zoneType = value.zoneType match {
            case "RT" => StorageCondition.RoomTemperature
            case "RF" => StorageCondition.Refrigerated
            case "FZ" => StorageCondition.Frozen
            case _ => throw new IllegalArgumentException(s"Unknown zone type: ${value.zoneType}")
          },
          capacity = ZoneCapacity.parseFromBigDecimal(BigDecimal(value.capacity)).getOrElse(
            throw new IllegalArgumentException(s"Invalid capacity: ${value.capacity}")
          ),
          occurredAt = DateTime.fromSecondsAndNanos(value.occurredAt.get.seconds, value.occurredAt.get.nanos)
        )

      case ("WarehouseZoneEvent.Updated", "V1") =>
        val value = WarehouseZoneEvent_Updated_V1.parseFrom(envelope.payload.toByteArray)
        DomainWarehouseZoneEvent.Updated_V1(
          id = DomainEventId.from(value.eventId),
          entityId = DomainWarehouseZoneId.from(value.warehouseZoneId),
          oldName = ZoneName.from(value.oldName),
          newName = ZoneName.from(value.newName),
          oldCapacity = ZoneCapacity.parseFromBigDecimal(BigDecimal(value.oldCapacity)).getOrElse(
            throw new IllegalArgumentException(s"Invalid old capacity: ${value.oldCapacity}")
          ),
          newCapacity = ZoneCapacity.parseFromBigDecimal(BigDecimal(value.newCapacity)).getOrElse(
            throw new IllegalArgumentException(s"Invalid new capacity: ${value.newCapacity}")
          ),
          occurredAt = DateTime.fromSecondsAndNanos(value.occurredAt.get.seconds, value.occurredAt.get.nanos)
        )

      case ("WarehouseZoneEvent.Deactivated", "V1") =>
        val value = WarehouseZoneEvent_Deactivated_V1.parseFrom(envelope.payload.toByteArray)
        DomainWarehouseZoneEvent.Deactivated_V1(
          id = DomainEventId.from(value.eventId),
          entityId = DomainWarehouseZoneId.from(value.warehouseZoneId),
          occurredAt = DateTime.fromSecondsAndNanos(value.occurredAt.get.seconds, value.occurredAt.get.nanos)
        )

      case ("WarehouseZoneEvent.Reactivated", "V1") =>
        val value = WarehouseZoneEvent_Reactivated_V1.parseFrom(envelope.payload.toByteArray)
        DomainWarehouseZoneEvent.Reactivated_V1(
          id = DomainEventId.from(value.eventId),
          entityId = DomainWarehouseZoneId.from(value.warehouseZoneId),
          occurredAt = DateTime.fromSecondsAndNanos(value.occurredAt.get.seconds, value.occurredAt.get.nanos)
        )

      case (name, ver) =>
        throw new IllegalArgumentException(s"Unexpected event type: name=$name, version=$ver in WarehouseZoneEvent_Envelope")
    }
  }
}
