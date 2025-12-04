package io.github.j5ik2o.pcqrses.command.interfaceAdapter.graphql.schema

import io.github.j5ik2o.pcqrses.command.interfaceAdapter.graphql.ResolverContext
import sangria.schema.*

/**
 * Inventory関連のGraphQL型定義
 */
trait InventoryTypeDefinitions extends ScalarTypes {

  // ========== Product Types ==========

  case class CreateProductResult(id: String)
  case class CreateProductInput(
      productCode: String,
      name: String,
      categoryCode: String,
      storageCondition: String // "RT", "RF", "FZ"
  )

  case class UpdateProductResult(id: String)
  case class UpdateProductInput(
      id: String,
      name: String,
      categoryCode: String,
      storageCondition: String
  )

  case class ObsoleteProductResult(id: String)
  case class ObsoleteProductInput(id: String)

  val CreateProductResultType: ObjectType[ResolverContext, CreateProductResult] =
    ObjectType(
      "CreateProductResult",
      fields[ResolverContext, CreateProductResult](
        Field("id", StringType, resolve = _.value.id)
      )
    )

  val UpdateProductResultType: ObjectType[ResolverContext, UpdateProductResult] =
    ObjectType(
      "UpdateProductResult",
      fields[ResolverContext, UpdateProductResult](
        Field("id", StringType, resolve = _.value.id)
      )
    )

  val ObsoleteProductResultType: ObjectType[ResolverContext, ObsoleteProductResult] =
    ObjectType(
      "ObsoleteProductResult",
      fields[ResolverContext, ObsoleteProductResult](
        Field("id", StringType, resolve = _.value.id)
      )
    )

  val CreateProductInputArg: Argument[CreateProductInput] = {
    import sangria.marshalling.circe.*
    import io.circe.generic.semiauto.*

    implicit val decoder: io.circe.Decoder[CreateProductInput] = deriveDecoder
    implicit val encoder: io.circe.Encoder[CreateProductInput] = deriveEncoder
    implicit val fromInput: sangria.marshalling.FromInput[CreateProductInput] =
      circeDecoderFromInput

    Argument(
      "input",
      InputObjectType(
        "CreateProductInput",
        List(
          InputField("productCode", StringType),
          InputField("name", StringType),
          InputField("categoryCode", StringType),
          InputField("storageCondition", StringType)
        )
      )
    )
  }

  val UpdateProductInputArg: Argument[UpdateProductInput] = {
    import sangria.marshalling.circe.*
    import io.circe.generic.semiauto.*

    implicit val decoder: io.circe.Decoder[UpdateProductInput] = deriveDecoder
    implicit val encoder: io.circe.Encoder[UpdateProductInput] = deriveEncoder
    implicit val fromInput: sangria.marshalling.FromInput[UpdateProductInput] =
      circeDecoderFromInput

    Argument(
      "input",
      InputObjectType(
        "UpdateProductInput",
        List(
          InputField("id", StringType),
          InputField("name", StringType),
          InputField("categoryCode", StringType),
          InputField("storageCondition", StringType)
        )
      )
    )
  }

  val ObsoleteProductInputArg: Argument[ObsoleteProductInput] = {
    import sangria.marshalling.circe.*
    import io.circe.generic.semiauto.*

    implicit val decoder: io.circe.Decoder[ObsoleteProductInput] = deriveDecoder
    implicit val encoder: io.circe.Encoder[ObsoleteProductInput] = deriveEncoder
    implicit val fromInput: sangria.marshalling.FromInput[ObsoleteProductInput] =
      circeDecoderFromInput

    Argument(
      "input",
      InputObjectType(
        "ObsoleteProductInput",
        List(InputField("id", StringType))
      )
    )
  }

  // ========== Inventory Types ==========

  case class CreateInventoryResult(id: String)
  case class CreateInventoryInput(productId: String, warehouseZoneId: String)

  case class ReceiveInventoryResult(version: Long)
  case class ReceiveInventoryInput(id: String, quantity: Double, expectedVersion: Long)

  case class ReserveInventoryResult(version: Long)
  case class ReserveInventoryInput(id: String, quantity: Double, expectedVersion: Long)

  case class ReleaseInventoryResult(version: Long)
  case class ReleaseInventoryInput(id: String, quantity: Double, expectedVersion: Long)

  case class IssueInventoryResult(version: Long)
  case class IssueInventoryInput(id: String, quantity: Double, expectedVersion: Long)

  case class AdjustInventoryResult(version: Long)
  case class AdjustInventoryInput(
      id: String,
      newQuantity: Double,
      reason: String,
      expectedVersion: Long
  )

  val CreateInventoryResultType: ObjectType[ResolverContext, CreateInventoryResult] =
    ObjectType(
      "CreateInventoryResult",
      fields[ResolverContext, CreateInventoryResult](
        Field("id", StringType, resolve = _.value.id)
      )
    )

  val InventoryVersionResultType: ObjectType[ResolverContext, Long] =
    ObjectType(
      "InventoryVersionResult",
      fields[ResolverContext, Long](
        Field("version", LongType, resolve = _.value)
      )
    )

  val CreateInventoryInputArg: Argument[CreateInventoryInput] = {
    import sangria.marshalling.circe.*
    import io.circe.generic.semiauto.*

    implicit val decoder: io.circe.Decoder[CreateInventoryInput] = deriveDecoder
    implicit val encoder: io.circe.Encoder[CreateInventoryInput] = deriveEncoder
    implicit val fromInput: sangria.marshalling.FromInput[CreateInventoryInput] =
      circeDecoderFromInput

    Argument(
      "input",
      InputObjectType(
        "CreateInventoryInput",
        List(
          InputField("productId", StringType),
          InputField("warehouseZoneId", StringType)
        )
      )
    )
  }

  val ReceiveInventoryInputArg: Argument[ReceiveInventoryInput] = {
    import sangria.marshalling.circe.*
    import io.circe.generic.semiauto.*

    implicit val decoder: io.circe.Decoder[ReceiveInventoryInput] = deriveDecoder
    implicit val encoder: io.circe.Encoder[ReceiveInventoryInput] = deriveEncoder
    implicit val fromInput: sangria.marshalling.FromInput[ReceiveInventoryInput] =
      circeDecoderFromInput

    Argument(
      "input",
      InputObjectType(
        "ReceiveInventoryInput",
        List(
          InputField("id", StringType),
          InputField("quantity", FloatType),
          InputField("expectedVersion", LongType)
        )
      )
    )
  }

  val ReserveInventoryInputArg: Argument[ReserveInventoryInput] = {
    import sangria.marshalling.circe.*
    import io.circe.generic.semiauto.*

    implicit val decoder: io.circe.Decoder[ReserveInventoryInput] = deriveDecoder
    implicit val encoder: io.circe.Encoder[ReserveInventoryInput] = deriveEncoder
    implicit val fromInput: sangria.marshalling.FromInput[ReserveInventoryInput] =
      circeDecoderFromInput

    Argument(
      "input",
      InputObjectType(
        "ReserveInventoryInput",
        List(
          InputField("id", StringType),
          InputField("quantity", FloatType),
          InputField("expectedVersion", LongType)
        )
      )
    )
  }

  val ReleaseInventoryInputArg: Argument[ReleaseInventoryInput] = {
    import sangria.marshalling.circe.*
    import io.circe.generic.semiauto.*

    implicit val decoder: io.circe.Decoder[ReleaseInventoryInput] = deriveDecoder
    implicit val encoder: io.circe.Encoder[ReleaseInventoryInput] = deriveEncoder
    implicit val fromInput: sangria.marshalling.FromInput[ReleaseInventoryInput] =
      circeDecoderFromInput

    Argument(
      "input",
      InputObjectType(
        "ReleaseInventoryInput",
        List(
          InputField("id", StringType),
          InputField("quantity", FloatType),
          InputField("expectedVersion", LongType)
        )
      )
    )
  }

  val IssueInventoryInputArg: Argument[IssueInventoryInput] = {
    import sangria.marshalling.circe.*
    import io.circe.generic.semiauto.*

    implicit val decoder: io.circe.Decoder[IssueInventoryInput] = deriveDecoder
    implicit val encoder: io.circe.Encoder[IssueInventoryInput] = deriveEncoder
    implicit val fromInput: sangria.marshalling.FromInput[IssueInventoryInput] =
      circeDecoderFromInput

    Argument(
      "input",
      InputObjectType(
        "IssueInventoryInput",
        List(
          InputField("id", StringType),
          InputField("quantity", FloatType),
          InputField("expectedVersion", LongType)
        )
      )
    )
  }

  val AdjustInventoryInputArg: Argument[AdjustInventoryInput] = {
    import sangria.marshalling.circe.*
    import io.circe.generic.semiauto.*

    implicit val decoder: io.circe.Decoder[AdjustInventoryInput] = deriveDecoder
    implicit val encoder: io.circe.Encoder[AdjustInventoryInput] = deriveEncoder
    implicit val fromInput: sangria.marshalling.FromInput[AdjustInventoryInput] =
      circeDecoderFromInput

    Argument(
      "input",
      InputObjectType(
        "AdjustInventoryInput",
        List(
          InputField("id", StringType),
          InputField("newQuantity", FloatType),
          InputField("reason", StringType),
          InputField("expectedVersion", LongType)
        )
      )
    )
  }

  // ========== Customer Types ==========

  case class CreateCustomerResult(id: String)
  case class CreateCustomerInput(
      customerCode: String,
      name: String,
      email: String,
      phone: String,
      address: String
  )

  case class UpdateCustomerResult(id: String)
  case class UpdateCustomerInput(
      id: String,
      name: String,
      email: String,
      phone: String,
      address: String
  )

  case class DeactivateCustomerResult(id: String)
  case class DeactivateCustomerInput(id: String)

  case class ReactivateCustomerResult(id: String)
  case class ReactivateCustomerInput(id: String)

  val CreateCustomerResultType: ObjectType[ResolverContext, CreateCustomerResult] =
    ObjectType(
      "CreateCustomerResult",
      fields[ResolverContext, CreateCustomerResult](
        Field("id", StringType, resolve = _.value.id)
      )
    )

  val UpdateCustomerResultType: ObjectType[ResolverContext, UpdateCustomerResult] =
    ObjectType(
      "UpdateCustomerResult",
      fields[ResolverContext, UpdateCustomerResult](
        Field("id", StringType, resolve = _.value.id)
      )
    )

  val DeactivateCustomerResultType: ObjectType[ResolverContext, DeactivateCustomerResult] =
    ObjectType(
      "DeactivateCustomerResult",
      fields[ResolverContext, DeactivateCustomerResult](
        Field("id", StringType, resolve = _.value.id)
      )
    )

  val ReactivateCustomerResultType: ObjectType[ResolverContext, ReactivateCustomerResult] =
    ObjectType(
      "ReactivateCustomerResult",
      fields[ResolverContext, ReactivateCustomerResult](
        Field("id", StringType, resolve = _.value.id)
      )
    )

  val CreateCustomerInputArg: Argument[CreateCustomerInput] = {
    import sangria.marshalling.circe.*
    import io.circe.generic.semiauto.*

    implicit val decoder: io.circe.Decoder[CreateCustomerInput] = deriveDecoder
    implicit val encoder: io.circe.Encoder[CreateCustomerInput] = deriveEncoder
    implicit val fromInput: sangria.marshalling.FromInput[CreateCustomerInput] =
      circeDecoderFromInput

    Argument(
      "input",
      InputObjectType(
        "CreateCustomerInput",
        List(
          InputField("customerCode", StringType),
          InputField("name", StringType),
          InputField("email", StringType),
          InputField("phone", StringType),
          InputField("address", StringType)
        )
      )
    )
  }

  val UpdateCustomerInputArg: Argument[UpdateCustomerInput] = {
    import sangria.marshalling.circe.*
    import io.circe.generic.semiauto.*

    implicit val decoder: io.circe.Decoder[UpdateCustomerInput] = deriveDecoder
    implicit val encoder: io.circe.Encoder[UpdateCustomerInput] = deriveEncoder
    implicit val fromInput: sangria.marshalling.FromInput[UpdateCustomerInput] =
      circeDecoderFromInput

    Argument(
      "input",
      InputObjectType(
        "UpdateCustomerInput",
        List(
          InputField("id", StringType),
          InputField("name", StringType),
          InputField("email", StringType),
          InputField("phone", StringType),
          InputField("address", StringType)
        )
      )
    )
  }

  val DeactivateCustomerInputArg: Argument[DeactivateCustomerInput] = {
    import sangria.marshalling.circe.*
    import io.circe.generic.semiauto.*

    implicit val decoder: io.circe.Decoder[DeactivateCustomerInput] = deriveDecoder
    implicit val encoder: io.circe.Encoder[DeactivateCustomerInput] = deriveEncoder
    implicit val fromInput: sangria.marshalling.FromInput[DeactivateCustomerInput] =
      circeDecoderFromInput

    Argument(
      "input",
      InputObjectType(
        "DeactivateCustomerInput",
        List(InputField("id", StringType))
      )
    )
  }

  val ReactivateCustomerInputArg: Argument[ReactivateCustomerInput] = {
    import sangria.marshalling.circe.*
    import io.circe.generic.semiauto.*

    implicit val decoder: io.circe.Decoder[ReactivateCustomerInput] = deriveDecoder
    implicit val encoder: io.circe.Encoder[ReactivateCustomerInput] = deriveEncoder
    implicit val fromInput: sangria.marshalling.FromInput[ReactivateCustomerInput] =
      circeDecoderFromInput

    Argument(
      "input",
      InputObjectType(
        "ReactivateCustomerInput",
        List(InputField("id", StringType))
      )
    )
  }

  // ========== Warehouse Types ==========

  case class CreateWarehouseResult(id: String)
  case class CreateWarehouseInput(
      warehouseCode: String,
      name: String,
      address: String
  )

  case class UpdateWarehouseResult(id: String)
  case class UpdateWarehouseInput(
      id: String,
      name: String,
      address: String
  )

  case class DeactivateWarehouseResult(id: String)
  case class DeactivateWarehouseInput(id: String)

  case class ReactivateWarehouseResult(id: String)
  case class ReactivateWarehouseInput(id: String)

  val CreateWarehouseResultType: ObjectType[ResolverContext, CreateWarehouseResult] =
    ObjectType(
      "CreateWarehouseResult",
      fields[ResolverContext, CreateWarehouseResult](
        Field("id", StringType, resolve = _.value.id)
      )
    )

  val UpdateWarehouseResultType: ObjectType[ResolverContext, UpdateWarehouseResult] =
    ObjectType(
      "UpdateWarehouseResult",
      fields[ResolverContext, UpdateWarehouseResult](
        Field("id", StringType, resolve = _.value.id)
      )
    )

  val DeactivateWarehouseResultType: ObjectType[ResolverContext, DeactivateWarehouseResult] =
    ObjectType(
      "DeactivateWarehouseResult",
      fields[ResolverContext, DeactivateWarehouseResult](
        Field("id", StringType, resolve = _.value.id)
      )
    )

  val ReactivateWarehouseResultType: ObjectType[ResolverContext, ReactivateWarehouseResult] =
    ObjectType(
      "ReactivateWarehouseResult",
      fields[ResolverContext, ReactivateWarehouseResult](
        Field("id", StringType, resolve = _.value.id)
      )
    )

  val CreateWarehouseInputArg: Argument[CreateWarehouseInput] = {
    import sangria.marshalling.circe.*
    import io.circe.generic.semiauto.*

    implicit val decoder: io.circe.Decoder[CreateWarehouseInput] = deriveDecoder
    implicit val encoder: io.circe.Encoder[CreateWarehouseInput] = deriveEncoder
    implicit val fromInput: sangria.marshalling.FromInput[CreateWarehouseInput] =
      circeDecoderFromInput

    Argument(
      "input",
      InputObjectType(
        "CreateWarehouseInput",
        List(
          InputField("warehouseCode", StringType),
          InputField("name", StringType),
          InputField("address", StringType)
        )
      )
    )
  }

  val UpdateWarehouseInputArg: Argument[UpdateWarehouseInput] = {
    import sangria.marshalling.circe.*
    import io.circe.generic.semiauto.*

    implicit val decoder: io.circe.Decoder[UpdateWarehouseInput] = deriveDecoder
    implicit val encoder: io.circe.Encoder[UpdateWarehouseInput] = deriveEncoder
    implicit val fromInput: sangria.marshalling.FromInput[UpdateWarehouseInput] =
      circeDecoderFromInput

    Argument(
      "input",
      InputObjectType(
        "UpdateWarehouseInput",
        List(
          InputField("id", StringType),
          InputField("name", StringType),
          InputField("address", StringType)
        )
      )
    )
  }

  val DeactivateWarehouseInputArg: Argument[DeactivateWarehouseInput] = {
    import sangria.marshalling.circe.*
    import io.circe.generic.semiauto.*

    implicit val decoder: io.circe.Decoder[DeactivateWarehouseInput] = deriveDecoder
    implicit val encoder: io.circe.Encoder[DeactivateWarehouseInput] = deriveEncoder
    implicit val fromInput: sangria.marshalling.FromInput[DeactivateWarehouseInput] =
      circeDecoderFromInput

    Argument(
      "input",
      InputObjectType(
        "DeactivateWarehouseInput",
        List(InputField("id", StringType))
      )
    )
  }

  val ReactivateWarehouseInputArg: Argument[ReactivateWarehouseInput] = {
    import sangria.marshalling.circe.*
    import io.circe.generic.semiauto.*

    implicit val decoder: io.circe.Decoder[ReactivateWarehouseInput] = deriveDecoder
    implicit val encoder: io.circe.Encoder[ReactivateWarehouseInput] = deriveEncoder
    implicit val fromInput: sangria.marshalling.FromInput[ReactivateWarehouseInput] =
      circeDecoderFromInput

    Argument(
      "input",
      InputObjectType(
        "ReactivateWarehouseInput",
        List(InputField("id", StringType))
      )
    )
  }

  // ========== WarehouseZone Types ==========

  case class CreateWarehouseZoneResult(id: String)
  case class CreateWarehouseZoneInput(
      warehouseId: String,
      zoneCode: String,
      name: String,
      storageCondition: String // "RT", "RF", "FZ"
  )

  case class UpdateWarehouseZoneResult(id: String)
  case class UpdateWarehouseZoneInput(
      id: String,
      name: String,
      storageCondition: String
  )

  case class DeactivateWarehouseZoneResult(id: String)
  case class DeactivateWarehouseZoneInput(id: String)

  case class ReactivateWarehouseZoneResult(id: String)
  case class ReactivateWarehouseZoneInput(id: String)

  val CreateWarehouseZoneResultType: ObjectType[ResolverContext, CreateWarehouseZoneResult] =
    ObjectType(
      "CreateWarehouseZoneResult",
      fields[ResolverContext, CreateWarehouseZoneResult](
        Field("id", StringType, resolve = _.value.id)
      )
    )

  val UpdateWarehouseZoneResultType: ObjectType[ResolverContext, UpdateWarehouseZoneResult] =
    ObjectType(
      "UpdateWarehouseZoneResult",
      fields[ResolverContext, UpdateWarehouseZoneResult](
        Field("id", StringType, resolve = _.value.id)
      )
    )

  val DeactivateWarehouseZoneResultType: ObjectType[ResolverContext, DeactivateWarehouseZoneResult] =
    ObjectType(
      "DeactivateWarehouseZoneResult",
      fields[ResolverContext, DeactivateWarehouseZoneResult](
        Field("id", StringType, resolve = _.value.id)
      )
    )

  val ReactivateWarehouseZoneResultType: ObjectType[ResolverContext, ReactivateWarehouseZoneResult] =
    ObjectType(
      "ReactivateWarehouseZoneResult",
      fields[ResolverContext, ReactivateWarehouseZoneResult](
        Field("id", StringType, resolve = _.value.id)
      )
    )

  val CreateWarehouseZoneInputArg: Argument[CreateWarehouseZoneInput] = {
    import sangria.marshalling.circe.*
    import io.circe.generic.semiauto.*

    implicit val decoder: io.circe.Decoder[CreateWarehouseZoneInput] = deriveDecoder
    implicit val encoder: io.circe.Encoder[CreateWarehouseZoneInput] = deriveEncoder
    implicit val fromInput: sangria.marshalling.FromInput[CreateWarehouseZoneInput] =
      circeDecoderFromInput

    Argument(
      "input",
      InputObjectType(
        "CreateWarehouseZoneInput",
        List(
          InputField("warehouseId", StringType),
          InputField("zoneCode", StringType),
          InputField("name", StringType),
          InputField("storageCondition", StringType)
        )
      )
    )
  }

  val UpdateWarehouseZoneInputArg: Argument[UpdateWarehouseZoneInput] = {
    import sangria.marshalling.circe.*
    import io.circe.generic.semiauto.*

    implicit val decoder: io.circe.Decoder[UpdateWarehouseZoneInput] = deriveDecoder
    implicit val encoder: io.circe.Encoder[UpdateWarehouseZoneInput] = deriveEncoder
    implicit val fromInput: sangria.marshalling.FromInput[UpdateWarehouseZoneInput] =
      circeDecoderFromInput

    Argument(
      "input",
      InputObjectType(
        "UpdateWarehouseZoneInput",
        List(
          InputField("id", StringType),
          InputField("name", StringType),
          InputField("storageCondition", StringType)
        )
      )
    )
  }

  val DeactivateWarehouseZoneInputArg: Argument[DeactivateWarehouseZoneInput] = {
    import sangria.marshalling.circe.*
    import io.circe.generic.semiauto.*

    implicit val decoder: io.circe.Decoder[DeactivateWarehouseZoneInput] = deriveDecoder
    implicit val encoder: io.circe.Encoder[DeactivateWarehouseZoneInput] = deriveEncoder
    implicit val fromInput: sangria.marshalling.FromInput[DeactivateWarehouseZoneInput] =
      circeDecoderFromInput

    Argument(
      "input",
      InputObjectType(
        "DeactivateWarehouseZoneInput",
        List(InputField("id", StringType))
      )
    )
  }

  val ReactivateWarehouseZoneInputArg: Argument[ReactivateWarehouseZoneInput] = {
    import sangria.marshalling.circe.*
    import io.circe.generic.semiauto.*

    implicit val decoder: io.circe.Decoder[ReactivateWarehouseZoneInput] = deriveDecoder
    implicit val encoder: io.circe.Encoder[ReactivateWarehouseZoneInput] = deriveEncoder
    implicit val fromInput: sangria.marshalling.FromInput[ReactivateWarehouseZoneInput] =
      circeDecoderFromInput

    Argument(
      "input",
      InputObjectType(
        "ReactivateWarehouseZoneInput",
        List(InputField("id", StringType))
      )
    )
  }
}
