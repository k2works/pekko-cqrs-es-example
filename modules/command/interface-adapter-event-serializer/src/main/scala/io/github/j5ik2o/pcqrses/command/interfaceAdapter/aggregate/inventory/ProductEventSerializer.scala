package io.github.j5ik2o.pcqrses.command.interfaceAdapter.aggregate.inventory

import io.github.j5ik2o.pcqrses.command.domain.basic.DateTime
import io.github.j5ik2o.pcqrses.command.domain.inventory.{
  CategoryCode,
  ProductCode,
  ProductEvent as DomainProductEvent,
  ProductId as DomainProductId,
  ProductName,
  StorageCondition
}
import io.github.j5ik2o.pcqrses.command.domain.support.DomainEventId
import io.github.j5ik2o.pcqrses.command.interfaceAdapter.persistence.inventory.*
import org.apache.pekko.serialization.SerializerWithStringManifest

class ProductEventSerializer extends SerializerWithStringManifest {

  override def identifier: Int = 30001

  override def manifest(o: AnyRef): String = "Envelope"

  override def toBinary(o: AnyRef): Array[Byte] = {
    val envelope: ProductEvent_Envelope = o match {
      case DomainProductEvent.Created_V1(id, entityId, productCode, name, categoryCode, storageCondition, occurredAt) =>
        val payload = ProductEvent_Created_V1(
          eventId = id.asString,
          productId = entityId.asString,
          productCode = productCode.asString,
          name = name.asString,
          categoryCode = categoryCode.asString,
          storageCondition = storageCondition.code,
          occurredAt = {
            val sn = occurredAt.toSecondsAndNanos
            Some(com.google.protobuf.timestamp.Timestamp(sn._1, sn._2))
          }
        )
        ProductEvent_Envelope(
          productId = entityId.asString,
          eventTypeName = "ProductEvent.Created",
          eventTypeVersion = "V1",
          payload = com.google.protobuf.ByteString.copyFrom(payload.toByteArray),
          occurredAt = {
            val sn = occurredAt.toSecondsAndNanos
            Some(com.google.protobuf.timestamp.Timestamp(sn._1, sn._2))
          }
        )

      case DomainProductEvent.Updated_V1(id, entityId, oldName, newName, oldCategoryCode, newCategoryCode, oldStorageCondition, newStorageCondition, occurredAt) =>
        val payload = ProductEvent_Updated_V1(
          eventId = id.asString,
          productId = entityId.asString,
          oldName = oldName.asString,
          newName = newName.asString,
          oldCategoryCode = oldCategoryCode.asString,
          newCategoryCode = newCategoryCode.asString,
          oldStorageCondition = oldStorageCondition.code,
          newStorageCondition = newStorageCondition.code,
          occurredAt = {
            val sn = occurredAt.toSecondsAndNanos
            Some(com.google.protobuf.timestamp.Timestamp(sn._1, sn._2))
          }
        )
        ProductEvent_Envelope(
          productId = entityId.asString,
          eventTypeName = "ProductEvent.Updated",
          eventTypeVersion = "V1",
          payload = com.google.protobuf.ByteString.copyFrom(payload.toByteArray),
          occurredAt = {
            val sn = occurredAt.toSecondsAndNanos
            Some(com.google.protobuf.timestamp.Timestamp(sn._1, sn._2))
          }
        )

      case DomainProductEvent.Obsoleted_V1(id, entityId, occurredAt) =>
        val payload = ProductEvent_Obsoleted_V1(
          eventId = id.asString,
          productId = entityId.asString,
          occurredAt = {
            val sn = occurredAt.toSecondsAndNanos
            Some(com.google.protobuf.timestamp.Timestamp(sn._1, sn._2))
          }
        )
        ProductEvent_Envelope(
          productId = entityId.asString,
          eventTypeName = "ProductEvent.Obsoleted",
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
    val envelope = ProductEvent_Envelope.parseFrom(bytes)
    (envelope.eventTypeName, envelope.eventTypeVersion) match {
      case ("ProductEvent.Created", "V1") =>
        val value = ProductEvent_Created_V1.parseFrom(envelope.payload.toByteArray)
        DomainProductEvent.Created_V1(
          id = DomainEventId.from(value.eventId),
          entityId = DomainProductId.from(value.productId),
          productCode = ProductCode.from(value.productCode),
          name = ProductName.from(value.name),
          categoryCode = CategoryCode.from(value.categoryCode),
          storageCondition = value.storageCondition match {
            case "RT" => StorageCondition.RoomTemperature
            case "RF" => StorageCondition.Refrigerated
            case "FZ" => StorageCondition.Frozen
            case _ => throw new IllegalArgumentException(s"Unknown storage condition: ${value.storageCondition}")
          },
          occurredAt = DateTime.fromSecondsAndNanos(value.occurredAt.get.seconds, value.occurredAt.get.nanos)
        )

      case ("ProductEvent.Updated", "V1") =>
        val value = ProductEvent_Updated_V1.parseFrom(envelope.payload.toByteArray)
        DomainProductEvent.Updated_V1(
          id = DomainEventId.from(value.eventId),
          entityId = DomainProductId.from(value.productId),
          oldName = ProductName.from(value.oldName),
          newName = ProductName.from(value.newName),
          oldCategoryCode = CategoryCode.from(value.oldCategoryCode),
          newCategoryCode = CategoryCode.from(value.newCategoryCode),
          oldStorageCondition = value.oldStorageCondition match {
            case "RT" => StorageCondition.RoomTemperature
            case "RF" => StorageCondition.Refrigerated
            case "FZ" => StorageCondition.Frozen
            case _ => throw new IllegalArgumentException(s"Unknown storage condition: ${value.oldStorageCondition}")
          },
          newStorageCondition = value.newStorageCondition match {
            case "RT" => StorageCondition.RoomTemperature
            case "RF" => StorageCondition.Refrigerated
            case "FZ" => StorageCondition.Frozen
            case _ => throw new IllegalArgumentException(s"Unknown storage condition: ${value.newStorageCondition}")
          },
          occurredAt = DateTime.fromSecondsAndNanos(value.occurredAt.get.seconds, value.occurredAt.get.nanos)
        )

      case ("ProductEvent.Obsoleted", "V1") =>
        val value = ProductEvent_Obsoleted_V1.parseFrom(envelope.payload.toByteArray)
        DomainProductEvent.Obsoleted_V1(
          id = DomainEventId.from(value.eventId),
          entityId = DomainProductId.from(value.productId),
          occurredAt = DateTime.fromSecondsAndNanos(value.occurredAt.get.seconds, value.occurredAt.get.nanos)
        )

      case (name, ver) =>
        throw new IllegalArgumentException(s"Unexpected event type: name=$name, version=$ver in ProductEvent_Envelope")
    }
  }
}
