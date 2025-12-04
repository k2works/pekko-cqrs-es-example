package io.github.j5ik2o.pcqrses.command.interfaceAdapter.aggregate.inventory

import io.github.j5ik2o.pcqrses.command.domain.basic.DateTime
import io.github.j5ik2o.pcqrses.command.domain.inventory.{
  InventoryEvent as DomainInventoryEvent,
  InventoryId as DomainInventoryId,
  InventoryQuantity,
  InventoryVersion,
  ProductId as DomainProductId,
  WarehouseZoneId as DomainWarehouseZoneId
}
import io.github.j5ik2o.pcqrses.command.domain.support.DomainEventId
import io.github.j5ik2o.pcqrses.command.interfaceAdapter.persistence.inventory.*
import org.apache.pekko.serialization.SerializerWithStringManifest

class InventoryEventSerializer extends SerializerWithStringManifest {

  override def identifier: Int = 30002

  override def manifest(o: AnyRef): String = "Envelope"

  override def toBinary(o: AnyRef): Array[Byte] = {
    val envelope: InventoryEvent_Envelope = o match {
      case DomainInventoryEvent.Received_V1(id, entityId, productId, warehouseZoneId, quantity, version, occurredAt) =>
        val payload = InventoryEvent_Received_V1(
          eventId = id.asString,
          inventoryId = entityId.asString,
          productId = productId.asString,
          warehouseZoneId = warehouseZoneId.asString,
          quantity = quantity.amount.toString,
          version = version.value,
          occurredAt = {
            val sn = occurredAt.toSecondsAndNanos
            Some(com.google.protobuf.timestamp.Timestamp(sn._1, sn._2))
          }
        )
        InventoryEvent_Envelope(
          inventoryId = entityId.asString,
          eventTypeName = "InventoryEvent.Received",
          eventTypeVersion = "V1",
          payload = com.google.protobuf.ByteString.copyFrom(payload.toByteArray),
          occurredAt = {
            val sn = occurredAt.toSecondsAndNanos
            Some(com.google.protobuf.timestamp.Timestamp(sn._1, sn._2))
          }
        )

      case DomainInventoryEvent.Issued_V1(id, entityId, productId, warehouseZoneId, quantity, version, occurredAt) =>
        val payload = InventoryEvent_Issued_V1(
          eventId = id.asString,
          inventoryId = entityId.asString,
          productId = productId.asString,
          warehouseZoneId = warehouseZoneId.asString,
          quantity = quantity.amount.toString,
          version = version.value,
          occurredAt = {
            val sn = occurredAt.toSecondsAndNanos
            Some(com.google.protobuf.timestamp.Timestamp(sn._1, sn._2))
          }
        )
        InventoryEvent_Envelope(
          inventoryId = entityId.asString,
          eventTypeName = "InventoryEvent.Issued",
          eventTypeVersion = "V1",
          payload = com.google.protobuf.ByteString.copyFrom(payload.toByteArray),
          occurredAt = {
            val sn = occurredAt.toSecondsAndNanos
            Some(com.google.protobuf.timestamp.Timestamp(sn._1, sn._2))
          }
        )

      case DomainInventoryEvent.Reserved_V1(id, entityId, productId, warehouseZoneId, quantity, version, occurredAt) =>
        val payload = InventoryEvent_Reserved_V1(
          eventId = id.asString,
          inventoryId = entityId.asString,
          productId = productId.asString,
          warehouseZoneId = warehouseZoneId.asString,
          quantity = quantity.amount.toString,
          version = version.value,
          occurredAt = {
            val sn = occurredAt.toSecondsAndNanos
            Some(com.google.protobuf.timestamp.Timestamp(sn._1, sn._2))
          }
        )
        InventoryEvent_Envelope(
          inventoryId = entityId.asString,
          eventTypeName = "InventoryEvent.Reserved",
          eventTypeVersion = "V1",
          payload = com.google.protobuf.ByteString.copyFrom(payload.toByteArray),
          occurredAt = {
            val sn = occurredAt.toSecondsAndNanos
            Some(com.google.protobuf.timestamp.Timestamp(sn._1, sn._2))
          }
        )

      case DomainInventoryEvent.Released_V1(id, entityId, productId, warehouseZoneId, quantity, version, occurredAt) =>
        val payload = InventoryEvent_Released_V1(
          eventId = id.asString,
          inventoryId = entityId.asString,
          productId = productId.asString,
          warehouseZoneId = warehouseZoneId.asString,
          quantity = quantity.amount.toString,
          version = version.value,
          occurredAt = {
            val sn = occurredAt.toSecondsAndNanos
            Some(com.google.protobuf.timestamp.Timestamp(sn._1, sn._2))
          }
        )
        InventoryEvent_Envelope(
          inventoryId = entityId.asString,
          eventTypeName = "InventoryEvent.Released",
          eventTypeVersion = "V1",
          payload = com.google.protobuf.ByteString.copyFrom(payload.toByteArray),
          occurredAt = {
            val sn = occurredAt.toSecondsAndNanos
            Some(com.google.protobuf.timestamp.Timestamp(sn._1, sn._2))
          }
        )

      case DomainInventoryEvent.Adjusted_V1(id, entityId, productId, warehouseZoneId, oldQuantity, newQuantity, reason, version, occurredAt) =>
        val payload = InventoryEvent_Adjusted_V1(
          eventId = id.asString,
          inventoryId = entityId.asString,
          productId = productId.asString,
          warehouseZoneId = warehouseZoneId.asString,
          oldQuantity = oldQuantity.amount.toString,
          newQuantity = newQuantity.amount.toString,
          reason = reason,
          version = version.value,
          occurredAt = {
            val sn = occurredAt.toSecondsAndNanos
            Some(com.google.protobuf.timestamp.Timestamp(sn._1, sn._2))
          }
        )
        InventoryEvent_Envelope(
          inventoryId = entityId.asString,
          eventTypeName = "InventoryEvent.Adjusted",
          eventTypeVersion = "V1",
          payload = com.google.protobuf.ByteString.copyFrom(payload.toByteArray),
          occurredAt = {
            val sn = occurredAt.toSecondsAndNanos
            Some(com.google.protobuf.timestamp.Timestamp(sn._1, sn._2))
          }
        )

      case DomainInventoryEvent.Moved_V1(id, entityId, productId, fromWarehouseZoneId, toWarehouseZoneId, quantity, version, occurredAt) =>
        val payload = InventoryEvent_Moved_V1(
          eventId = id.asString,
          inventoryId = entityId.asString,
          productId = productId.asString,
          fromWarehouseZoneId = fromWarehouseZoneId.asString,
          toWarehouseZoneId = toWarehouseZoneId.asString,
          quantity = quantity.amount.toString,
          version = version.value,
          occurredAt = {
            val sn = occurredAt.toSecondsAndNanos
            Some(com.google.protobuf.timestamp.Timestamp(sn._1, sn._2))
          }
        )
        InventoryEvent_Envelope(
          inventoryId = entityId.asString,
          eventTypeName = "InventoryEvent.Moved",
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
    val envelope = InventoryEvent_Envelope.parseFrom(bytes)
    (envelope.eventTypeName, envelope.eventTypeVersion) match {
      case ("InventoryEvent.Received", "V1") =>
        val value = InventoryEvent_Received_V1.parseFrom(envelope.payload.toByteArray)
        DomainInventoryEvent.Received_V1(
          id = DomainEventId.from(value.eventId),
          entityId = DomainInventoryId.from(value.inventoryId),
          productId = DomainProductId.from(value.productId),
          warehouseZoneId = DomainWarehouseZoneId.from(value.warehouseZoneId),
          quantity = InventoryQuantity.parseFromBigDecimal(BigDecimal(value.quantity)).getOrElse(
            throw new IllegalArgumentException(s"Invalid quantity: ${value.quantity}")
          ),
          version = InventoryVersion.parseFromLong(value.version).getOrElse(
            throw new IllegalArgumentException(s"Invalid version: ${value.version}")
          ),
          occurredAt = DateTime.fromSecondsAndNanos(value.occurredAt.get.seconds, value.occurredAt.get.nanos)
        )

      case ("InventoryEvent.Issued", "V1") =>
        val value = InventoryEvent_Issued_V1.parseFrom(envelope.payload.toByteArray)
        DomainInventoryEvent.Issued_V1(
          id = DomainEventId.from(value.eventId),
          entityId = DomainInventoryId.from(value.inventoryId),
          productId = DomainProductId.from(value.productId),
          warehouseZoneId = DomainWarehouseZoneId.from(value.warehouseZoneId),
          quantity = InventoryQuantity.parseFromBigDecimal(BigDecimal(value.quantity)).getOrElse(
            throw new IllegalArgumentException(s"Invalid quantity: ${value.quantity}")
          ),
          version = InventoryVersion.parseFromLong(value.version).getOrElse(
            throw new IllegalArgumentException(s"Invalid version: ${value.version}")
          ),
          occurredAt = DateTime.fromSecondsAndNanos(value.occurredAt.get.seconds, value.occurredAt.get.nanos)
        )

      case ("InventoryEvent.Reserved", "V1") =>
        val value = InventoryEvent_Reserved_V1.parseFrom(envelope.payload.toByteArray)
        DomainInventoryEvent.Reserved_V1(
          id = DomainEventId.from(value.eventId),
          entityId = DomainInventoryId.from(value.inventoryId),
          productId = DomainProductId.from(value.productId),
          warehouseZoneId = DomainWarehouseZoneId.from(value.warehouseZoneId),
          quantity = InventoryQuantity.parseFromBigDecimal(BigDecimal(value.quantity)).getOrElse(
            throw new IllegalArgumentException(s"Invalid quantity: ${value.quantity}")
          ),
          version = InventoryVersion.parseFromLong(value.version).getOrElse(
            throw new IllegalArgumentException(s"Invalid version: ${value.version}")
          ),
          occurredAt = DateTime.fromSecondsAndNanos(value.occurredAt.get.seconds, value.occurredAt.get.nanos)
        )

      case ("InventoryEvent.Released", "V1") =>
        val value = InventoryEvent_Released_V1.parseFrom(envelope.payload.toByteArray)
        DomainInventoryEvent.Released_V1(
          id = DomainEventId.from(value.eventId),
          entityId = DomainInventoryId.from(value.inventoryId),
          productId = DomainProductId.from(value.productId),
          warehouseZoneId = DomainWarehouseZoneId.from(value.warehouseZoneId),
          quantity = InventoryQuantity.parseFromBigDecimal(BigDecimal(value.quantity)).getOrElse(
            throw new IllegalArgumentException(s"Invalid quantity: ${value.quantity}")
          ),
          version = InventoryVersion.parseFromLong(value.version).getOrElse(
            throw new IllegalArgumentException(s"Invalid version: ${value.version}")
          ),
          occurredAt = DateTime.fromSecondsAndNanos(value.occurredAt.get.seconds, value.occurredAt.get.nanos)
        )

      case ("InventoryEvent.Adjusted", "V1") =>
        val value = InventoryEvent_Adjusted_V1.parseFrom(envelope.payload.toByteArray)
        DomainInventoryEvent.Adjusted_V1(
          id = DomainEventId.from(value.eventId),
          entityId = DomainInventoryId.from(value.inventoryId),
          productId = DomainProductId.from(value.productId),
          warehouseZoneId = DomainWarehouseZoneId.from(value.warehouseZoneId),
          oldQuantity = InventoryQuantity.parseFromBigDecimal(BigDecimal(value.oldQuantity)).getOrElse(
            throw new IllegalArgumentException(s"Invalid old quantity: ${value.oldQuantity}")
          ),
          newQuantity = InventoryQuantity.parseFromBigDecimal(BigDecimal(value.newQuantity)).getOrElse(
            throw new IllegalArgumentException(s"Invalid new quantity: ${value.newQuantity}")
          ),
          reason = value.reason,
          version = InventoryVersion.parseFromLong(value.version).getOrElse(
            throw new IllegalArgumentException(s"Invalid version: ${value.version}")
          ),
          occurredAt = DateTime.fromSecondsAndNanos(value.occurredAt.get.seconds, value.occurredAt.get.nanos)
        )

      case ("InventoryEvent.Moved", "V1") =>
        val value = InventoryEvent_Moved_V1.parseFrom(envelope.payload.toByteArray)
        DomainInventoryEvent.Moved_V1(
          id = DomainEventId.from(value.eventId),
          entityId = DomainInventoryId.from(value.inventoryId),
          productId = DomainProductId.from(value.productId),
          fromWarehouseZoneId = DomainWarehouseZoneId.from(value.fromWarehouseZoneId),
          toWarehouseZoneId = DomainWarehouseZoneId.from(value.toWarehouseZoneId),
          quantity = InventoryQuantity.parseFromBigDecimal(BigDecimal(value.quantity)).getOrElse(
            throw new IllegalArgumentException(s"Invalid quantity: ${value.quantity}")
          ),
          version = InventoryVersion.parseFromLong(value.version).getOrElse(
            throw new IllegalArgumentException(s"Invalid version: ${value.version}")
          ),
          occurredAt = DateTime.fromSecondsAndNanos(value.occurredAt.get.seconds, value.occurredAt.get.nanos)
        )

      case (name, ver) =>
        throw new IllegalArgumentException(s"Unexpected event type: name=$name, version=$ver in InventoryEvent_Envelope")
    }
  }
}
