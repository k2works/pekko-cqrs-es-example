package io.github.j5ik2o.pcqrses.command.interfaceAdapter.aggregate.inventory

import io.github.j5ik2o.pcqrses.command.domain.basic.DateTime
import io.github.j5ik2o.pcqrses.command.domain.inventory.{
  CustomerCode,
  CustomerEvent as DomainCustomerEvent,
  CustomerId as DomainCustomerId,
  CustomerName,
  CustomerType
}
import io.github.j5ik2o.pcqrses.command.domain.support.DomainEventId
import io.github.j5ik2o.pcqrses.command.interfaceAdapter.persistence.inventory.*
import org.apache.pekko.serialization.SerializerWithStringManifest

class CustomerEventSerializer extends SerializerWithStringManifest {

  override def identifier: Int = 30003

  override def manifest(o: AnyRef): String = "Envelope"

  override def toBinary(o: AnyRef): Array[Byte] = {
    val envelope: CustomerEvent_Envelope = o match {
      case DomainCustomerEvent.Created_V1(id, entityId, customerCode, name, customerType, occurredAt) =>
        val payload = CustomerEvent_Created_V1(
          eventId = id.asString,
          customerId = entityId.asString,
          customerCode = customerCode.asString,
          name = name.asString,
          customerType = customerType.toString,
          occurredAt = {
            val sn = occurredAt.toSecondsAndNanos
            Some(com.google.protobuf.timestamp.Timestamp(sn._1, sn._2))
          }
        )
        CustomerEvent_Envelope(
          customerId = entityId.asString,
          eventTypeName = "CustomerEvent.Created",
          eventTypeVersion = "V1",
          payload = com.google.protobuf.ByteString.copyFrom(payload.toByteArray),
          occurredAt = {
            val sn = occurredAt.toSecondsAndNanos
            Some(com.google.protobuf.timestamp.Timestamp(sn._1, sn._2))
          }
        )

      case DomainCustomerEvent.Updated_V1(id, entityId, oldName, newName, oldCustomerType, newCustomerType, occurredAt) =>
        val payload = CustomerEvent_Updated_V1(
          eventId = id.asString,
          customerId = entityId.asString,
          oldName = oldName.asString,
          newName = newName.asString,
          oldCustomerType = oldCustomerType.toString,
          newCustomerType = newCustomerType.toString,
          occurredAt = {
            val sn = occurredAt.toSecondsAndNanos
            Some(com.google.protobuf.timestamp.Timestamp(sn._1, sn._2))
          }
        )
        CustomerEvent_Envelope(
          customerId = entityId.asString,
          eventTypeName = "CustomerEvent.Updated",
          eventTypeVersion = "V1",
          payload = com.google.protobuf.ByteString.copyFrom(payload.toByteArray),
          occurredAt = {
            val sn = occurredAt.toSecondsAndNanos
            Some(com.google.protobuf.timestamp.Timestamp(sn._1, sn._2))
          }
        )

      case DomainCustomerEvent.Deactivated_V1(id, entityId, occurredAt) =>
        val payload = CustomerEvent_Deactivated_V1(
          eventId = id.asString,
          customerId = entityId.asString,
          occurredAt = {
            val sn = occurredAt.toSecondsAndNanos
            Some(com.google.protobuf.timestamp.Timestamp(sn._1, sn._2))
          }
        )
        CustomerEvent_Envelope(
          customerId = entityId.asString,
          eventTypeName = "CustomerEvent.Deactivated",
          eventTypeVersion = "V1",
          payload = com.google.protobuf.ByteString.copyFrom(payload.toByteArray),
          occurredAt = {
            val sn = occurredAt.toSecondsAndNanos
            Some(com.google.protobuf.timestamp.Timestamp(sn._1, sn._2))
          }
        )

      case DomainCustomerEvent.Reactivated_V1(id, entityId, occurredAt) =>
        val payload = CustomerEvent_Reactivated_V1(
          eventId = id.asString,
          customerId = entityId.asString,
          occurredAt = {
            val sn = occurredAt.toSecondsAndNanos
            Some(com.google.protobuf.timestamp.Timestamp(sn._1, sn._2))
          }
        )
        CustomerEvent_Envelope(
          customerId = entityId.asString,
          eventTypeName = "CustomerEvent.Reactivated",
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
    val envelope = CustomerEvent_Envelope.parseFrom(bytes)
    (envelope.eventTypeName, envelope.eventTypeVersion) match {
      case ("CustomerEvent.Created", "V1") =>
        val value = CustomerEvent_Created_V1.parseFrom(envelope.payload.toByteArray)
        DomainCustomerEvent.Created_V1(
          id = DomainEventId.from(value.eventId),
          entityId = DomainCustomerId.from(value.customerId),
          customerCode = CustomerCode.from(value.customerCode),
          name = CustomerName.from(value.name),
          customerType = CustomerType.valueOf(value.customerType),
          occurredAt = DateTime.fromSecondsAndNanos(value.occurredAt.get.seconds, value.occurredAt.get.nanos)
        )

      case ("CustomerEvent.Updated", "V1") =>
        val value = CustomerEvent_Updated_V1.parseFrom(envelope.payload.toByteArray)
        DomainCustomerEvent.Updated_V1(
          id = DomainEventId.from(value.eventId),
          entityId = DomainCustomerId.from(value.customerId),
          oldName = CustomerName.from(value.oldName),
          newName = CustomerName.from(value.newName),
          oldCustomerType = CustomerType.valueOf(value.oldCustomerType),
          newCustomerType = CustomerType.valueOf(value.newCustomerType),
          occurredAt = DateTime.fromSecondsAndNanos(value.occurredAt.get.seconds, value.occurredAt.get.nanos)
        )

      case ("CustomerEvent.Deactivated", "V1") =>
        val value = CustomerEvent_Deactivated_V1.parseFrom(envelope.payload.toByteArray)
        DomainCustomerEvent.Deactivated_V1(
          id = DomainEventId.from(value.eventId),
          entityId = DomainCustomerId.from(value.customerId),
          occurredAt = DateTime.fromSecondsAndNanos(value.occurredAt.get.seconds, value.occurredAt.get.nanos)
        )

      case ("CustomerEvent.Reactivated", "V1") =>
        val value = CustomerEvent_Reactivated_V1.parseFrom(envelope.payload.toByteArray)
        DomainCustomerEvent.Reactivated_V1(
          id = DomainEventId.from(value.eventId),
          entityId = DomainCustomerId.from(value.customerId),
          occurredAt = DateTime.fromSecondsAndNanos(value.occurredAt.get.seconds, value.occurredAt.get.nanos)
        )

      case (name, ver) =>
        throw new IllegalArgumentException(s"Unexpected event type: name=$name, version=$ver in CustomerEvent_Envelope")
    }
  }
}
