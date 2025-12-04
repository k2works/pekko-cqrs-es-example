package io.github.j5ik2o.pcqrses.command.interfaceAdapter.aggregate.inventory

import io.github.j5ik2o.pcqrses.command.domain.basic.DateTime
import io.github.j5ik2o.pcqrses.command.domain.inventory.{
  WarehouseCode,
  WarehouseEvent as DomainWarehouseEvent,
  WarehouseId as DomainWarehouseId,
  WarehouseLocation,
  WarehouseName
}
import io.github.j5ik2o.pcqrses.command.domain.support.DomainEventId
import io.github.j5ik2o.pcqrses.command.interfaceAdapter.persistence.inventory.*
import org.apache.pekko.serialization.SerializerWithStringManifest

class WarehouseEventSerializer extends SerializerWithStringManifest {

  override def identifier: Int = 30004

  override def manifest(o: AnyRef): String = "Envelope"

  override def toBinary(o: AnyRef): Array[Byte] = {
    val envelope: WarehouseEvent_Envelope = o match {
      case DomainWarehouseEvent.Created_V1(id, entityId, warehouseCode, name, location, occurredAt) =>
        val payload = WarehouseEvent_Created_V1(
          eventId = id.asString,
          warehouseId = entityId.asString,
          warehouseCode = warehouseCode.asString,
          name = name.asString,
          location = location.asString,
          occurredAt = {
            val sn = occurredAt.toSecondsAndNanos
            Some(com.google.protobuf.timestamp.Timestamp(sn._1, sn._2))
          }
        )
        WarehouseEvent_Envelope(
          warehouseId = entityId.asString,
          eventTypeName = "WarehouseEvent.Created",
          eventTypeVersion = "V1",
          payload = com.google.protobuf.ByteString.copyFrom(payload.toByteArray),
          occurredAt = {
            val sn = occurredAt.toSecondsAndNanos
            Some(com.google.protobuf.timestamp.Timestamp(sn._1, sn._2))
          }
        )

      case DomainWarehouseEvent.Updated_V1(id, entityId, oldName, newName, oldLocation, newLocation, occurredAt) =>
        val payload = WarehouseEvent_Updated_V1(
          eventId = id.asString,
          warehouseId = entityId.asString,
          oldName = oldName.asString,
          newName = newName.asString,
          oldLocation = oldLocation.asString,
          newLocation = newLocation.asString,
          occurredAt = {
            val sn = occurredAt.toSecondsAndNanos
            Some(com.google.protobuf.timestamp.Timestamp(sn._1, sn._2))
          }
        )
        WarehouseEvent_Envelope(
          warehouseId = entityId.asString,
          eventTypeName = "WarehouseEvent.Updated",
          eventTypeVersion = "V1",
          payload = com.google.protobuf.ByteString.copyFrom(payload.toByteArray),
          occurredAt = {
            val sn = occurredAt.toSecondsAndNanos
            Some(com.google.protobuf.timestamp.Timestamp(sn._1, sn._2))
          }
        )

      case DomainWarehouseEvent.Deactivated_V1(id, entityId, occurredAt) =>
        val payload = WarehouseEvent_Deactivated_V1(
          eventId = id.asString,
          warehouseId = entityId.asString,
          occurredAt = {
            val sn = occurredAt.toSecondsAndNanos
            Some(com.google.protobuf.timestamp.Timestamp(sn._1, sn._2))
          }
        )
        WarehouseEvent_Envelope(
          warehouseId = entityId.asString,
          eventTypeName = "WarehouseEvent.Deactivated",
          eventTypeVersion = "V1",
          payload = com.google.protobuf.ByteString.copyFrom(payload.toByteArray),
          occurredAt = {
            val sn = occurredAt.toSecondsAndNanos
            Some(com.google.protobuf.timestamp.Timestamp(sn._1, sn._2))
          }
        )

      case DomainWarehouseEvent.Reactivated_V1(id, entityId, occurredAt) =>
        val payload = WarehouseEvent_Reactivated_V1(
          eventId = id.asString,
          warehouseId = entityId.asString,
          occurredAt = {
            val sn = occurredAt.toSecondsAndNanos
            Some(com.google.protobuf.timestamp.Timestamp(sn._1, sn._2))
          }
        )
        WarehouseEvent_Envelope(
          warehouseId = entityId.asString,
          eventTypeName = "WarehouseEvent.Reactivated",
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
    val envelope = WarehouseEvent_Envelope.parseFrom(bytes)
    (envelope.eventTypeName, envelope.eventTypeVersion) match {
      case ("WarehouseEvent.Created", "V1") =>
        val value = WarehouseEvent_Created_V1.parseFrom(envelope.payload.toByteArray)
        DomainWarehouseEvent.Created_V1(
          id = DomainEventId.from(value.eventId),
          entityId = DomainWarehouseId.from(value.warehouseId),
          warehouseCode = WarehouseCode.from(value.warehouseCode),
          name = WarehouseName.from(value.name),
          location = WarehouseLocation.from(value.location),
          occurredAt = DateTime.fromSecondsAndNanos(value.occurredAt.get.seconds, value.occurredAt.get.nanos)
        )

      case ("WarehouseEvent.Updated", "V1") =>
        val value = WarehouseEvent_Updated_V1.parseFrom(envelope.payload.toByteArray)
        DomainWarehouseEvent.Updated_V1(
          id = DomainEventId.from(value.eventId),
          entityId = DomainWarehouseId.from(value.warehouseId),
          oldName = WarehouseName.from(value.oldName),
          newName = WarehouseName.from(value.newName),
          oldLocation = WarehouseLocation.from(value.oldLocation),
          newLocation = WarehouseLocation.from(value.newLocation),
          occurredAt = DateTime.fromSecondsAndNanos(value.occurredAt.get.seconds, value.occurredAt.get.nanos)
        )

      case ("WarehouseEvent.Deactivated", "V1") =>
        val value = WarehouseEvent_Deactivated_V1.parseFrom(envelope.payload.toByteArray)
        DomainWarehouseEvent.Deactivated_V1(
          id = DomainEventId.from(value.eventId),
          entityId = DomainWarehouseId.from(value.warehouseId),
          occurredAt = DateTime.fromSecondsAndNanos(value.occurredAt.get.seconds, value.occurredAt.get.nanos)
        )

      case ("WarehouseEvent.Reactivated", "V1") =>
        val value = WarehouseEvent_Reactivated_V1.parseFrom(envelope.payload.toByteArray)
        DomainWarehouseEvent.Reactivated_V1(
          id = DomainEventId.from(value.eventId),
          entityId = DomainWarehouseId.from(value.warehouseId),
          occurredAt = DateTime.fromSecondsAndNanos(value.occurredAt.get.seconds, value.occurredAt.get.nanos)
        )

      case (name, ver) =>
        throw new IllegalArgumentException(s"Unexpected event type: name=$name, version=$ver in WarehouseEvent_Envelope")
    }
  }
}
