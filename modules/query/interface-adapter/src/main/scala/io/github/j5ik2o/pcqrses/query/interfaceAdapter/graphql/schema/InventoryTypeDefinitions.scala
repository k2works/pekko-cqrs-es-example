package io.github.j5ik2o.pcqrses.query.interfaceAdapter.graphql.schema

import io.github.j5ik2o.pcqrses.query.interfaceAdapter.dao.*
import io.github.j5ik2o.pcqrses.query.interfaceAdapter.graphql.ResolverContext
import sangria.schema.*
import ScalarTypes.*

import java.time.OffsetDateTime
import sangria.marshalling.FromInput.CoercedScalaResult
import sangria.util.tag.Tagged

trait InventoryTypeDefinitions {
  this: ProductsComponent
    & InventoriesComponent
    & CustomersComponent
    & WarehousesComponent
    & WarehouseZonesComponent
    & InventoryTransactionsComponent =>

  // ========== Product Types ==========

  val ProductIdArg: Argument[String] =
    Argument("productId", StringType, description = "ID of Product")

  val ProductIdsArg: Argument[Seq[String & Tagged[CoercedScalaResult] | Null]] =
    Argument("productIds", ListInputType(StringType), description = "List of Product IDs")

  val ProductCodeArg: Argument[String] =
    Argument("productCode", StringType, description = "Product Code")

  val CategoryCodeArg: Argument[String] =
    Argument("categoryCode", StringType, description = "Category Code")

  val StorageConditionArg: Argument[String] =
    Argument("storageCondition", StringType, description = "Storage Condition (RT/RF/FZ)")

  val ProductType: ObjectType[ResolverContext, ProductsRecord] = ObjectType(
    "Product",
    "Product information",
    fields[ResolverContext, ProductsRecord](
      Field("id", StringType, description = Some("Unique identifier"), resolve = _.value.id),
      Field("productCode", StringType, description = Some("Product code"), resolve = _.value.productCode),
      Field("name", StringType, description = Some("Product name"), resolve = _.value.name),
      Field("categoryCode", StringType, description = Some("Category code"), resolve = _.value.categoryCode),
      Field(
        "storageCondition",
        StringType,
        description = Some("Storage condition (RT/RF/FZ)"),
        resolve = _.value.storageCondition
      ),
      Field("isObsolete", BooleanType, description = Some("Is product obsolete"), resolve = _.value.isObsolete),
      Field(
        "createdAt",
        OffsetDateTimeType,
        description = Some("Creation timestamp"),
        resolve = t => OffsetDateTime.ofInstant(t.value.createdAt.toInstant, java.time.ZoneOffset.UTC)
      ),
      Field(
        "updatedAt",
        OffsetDateTimeType,
        description = Some("Last update timestamp"),
        resolve = t => OffsetDateTime.ofInstant(t.value.updatedAt.toInstant, java.time.ZoneOffset.UTC)
      )
    )
  )

  // ========== Inventory Types ==========

  val InventoryIdArg: Argument[String] =
    Argument("inventoryId", StringType, description = "ID of Inventory")

  val InventoryIdsArg: Argument[Seq[String & Tagged[CoercedScalaResult] | Null]] =
    Argument("inventoryIds", ListInputType(StringType), description = "List of Inventory IDs")

  val WarehouseZoneIdArg: Argument[String] =
    Argument("warehouseZoneId", StringType, description = "ID of WarehouseZone")

  val InventoryType: ObjectType[ResolverContext, InventoriesRecord] = ObjectType(
    "Inventory",
    "Inventory information",
    fields[ResolverContext, InventoriesRecord](
      Field("id", StringType, description = Some("Unique identifier"), resolve = _.value.id),
      Field("productId", StringType, description = Some("Product ID"), resolve = _.value.productId),
      Field(
        "warehouseZoneId",
        StringType,
        description = Some("Warehouse Zone ID"),
        resolve = _.value.warehouseZoneId
      ),
      Field(
        "availableQuantity",
        BigDecimalType,
        description = Some("Available quantity"),
        resolve = _.value.availableQuantity
      ),
      Field(
        "reservedQuantity",
        BigDecimalType,
        description = Some("Reserved quantity"),
        resolve = _.value.reservedQuantity
      ),
      Field("version", LongType, description = Some("Version for optimistic locking"), resolve = _.value.version),
      Field(
        "createdAt",
        OffsetDateTimeType,
        description = Some("Creation timestamp"),
        resolve = t => OffsetDateTime.ofInstant(t.value.createdAt.toInstant, java.time.ZoneOffset.UTC)
      ),
      Field(
        "updatedAt",
        OffsetDateTimeType,
        description = Some("Last update timestamp"),
        resolve = t => OffsetDateTime.ofInstant(t.value.updatedAt.toInstant, java.time.ZoneOffset.UTC)
      )
    )
  )

  // ========== Customer Types ==========

  val CustomerIdArg: Argument[String] =
    Argument("customerId", StringType, description = "ID of Customer")

  val CustomerIdsArg: Argument[Seq[String & Tagged[CoercedScalaResult] | Null]] =
    Argument("customerIds", ListInputType(StringType), description = "List of Customer IDs")

  val CustomerCodeArg: Argument[String] =
    Argument("customerCode", StringType, description = "Customer Code")

  val CustomerType: ObjectType[ResolverContext, CustomersRecord] = ObjectType(
    "Customer",
    "Customer information",
    fields[ResolverContext, CustomersRecord](
      Field("id", StringType, description = Some("Unique identifier"), resolve = _.value.id),
      Field("customerCode", StringType, description = Some("Customer code"), resolve = _.value.customerCode),
      Field("name", StringType, description = Some("Customer name"), resolve = _.value.name),
      Field("customerType", StringType, description = Some("Customer type"), resolve = _.value.customerType),
      Field("isActive", BooleanType, description = Some("Is customer active"), resolve = _.value.isActive),
      Field(
        "createdAt",
        OffsetDateTimeType,
        description = Some("Creation timestamp"),
        resolve = t => OffsetDateTime.ofInstant(t.value.createdAt.toInstant, java.time.ZoneOffset.UTC)
      ),
      Field(
        "updatedAt",
        OffsetDateTimeType,
        description = Some("Last update timestamp"),
        resolve = t => OffsetDateTime.ofInstant(t.value.updatedAt.toInstant, java.time.ZoneOffset.UTC)
      )
    )
  )

  // ========== Warehouse Types ==========

  val WarehouseIdArg: Argument[String] =
    Argument("warehouseId", StringType, description = "ID of Warehouse")

  val WarehouseIdsArg: Argument[Seq[String & Tagged[CoercedScalaResult] | Null]] =
    Argument("warehouseIds", ListInputType(StringType), description = "List of Warehouse IDs")

  val WarehouseCodeArg: Argument[String] =
    Argument("warehouseCode", StringType, description = "Warehouse Code")

  val WarehouseType: ObjectType[ResolverContext, WarehousesRecord] = ObjectType(
    "Warehouse",
    "Warehouse information",
    fields[ResolverContext, WarehousesRecord](
      Field("id", StringType, description = Some("Unique identifier"), resolve = _.value.id),
      Field("warehouseCode", StringType, description = Some("Warehouse code"), resolve = _.value.warehouseCode),
      Field("name", StringType, description = Some("Warehouse name"), resolve = _.value.name),
      Field("location", StringType, description = Some("Location"), resolve = _.value.location),
      Field("isActive", BooleanType, description = Some("Is warehouse active"), resolve = _.value.isActive),
      Field(
        "createdAt",
        OffsetDateTimeType,
        description = Some("Creation timestamp"),
        resolve = t => OffsetDateTime.ofInstant(t.value.createdAt.toInstant, java.time.ZoneOffset.UTC)
      ),
      Field(
        "updatedAt",
        OffsetDateTimeType,
        description = Some("Last update timestamp"),
        resolve = t => OffsetDateTime.ofInstant(t.value.updatedAt.toInstant, java.time.ZoneOffset.UTC)
      )
    )
  )

  // ========== WarehouseZone Types ==========

  val WarehouseZoneIdsArg: Argument[Seq[String & Tagged[CoercedScalaResult] | Null]] =
    Argument("warehouseZoneIds", ListInputType(StringType), description = "List of WarehouseZone IDs")

  val ZoneCodeArg: Argument[String] =
    Argument("zoneCode", StringType, description = "Zone Code")

  val WarehouseZoneType: ObjectType[ResolverContext, WarehouseZonesRecord] = ObjectType(
    "WarehouseZone",
    "Warehouse zone information",
    fields[ResolverContext, WarehouseZonesRecord](
      Field("id", StringType, description = Some("Unique identifier"), resolve = _.value.id),
      Field("warehouseId", StringType, description = Some("Warehouse ID"), resolve = _.value.warehouseId),
      Field("zoneCode", StringType, description = Some("Zone code"), resolve = _.value.zoneCode),
      Field("name", StringType, description = Some("Zone name"), resolve = _.value.name),
      Field(
        "zoneType",
        StringType,
        description = Some("Zone type"),
        resolve = _.value.zoneType
      ),
      Field(
        "capacitySqm",
        BigDecimalType,
        description = Some("Capacity in square meters"),
        resolve = _.value.capacitySqm
      ),
      Field("isActive", BooleanType, description = Some("Is zone active"), resolve = _.value.isActive),
      Field(
        "createdAt",
        OffsetDateTimeType,
        description = Some("Creation timestamp"),
        resolve = t => OffsetDateTime.ofInstant(t.value.createdAt.toInstant, java.time.ZoneOffset.UTC)
      ),
      Field(
        "updatedAt",
        OffsetDateTimeType,
        description = Some("Last update timestamp"),
        resolve = t => OffsetDateTime.ofInstant(t.value.updatedAt.toInstant, java.time.ZoneOffset.UTC)
      )
    )
  )

  // ========== InventoryTransaction Types ==========

  val TransactionTypeArg: Argument[String] =
    Argument("transactionType", StringType, description = "Transaction Type")

  val InventoryTransactionType: ObjectType[ResolverContext, InventoryTransactionsRecord] = ObjectType(
    "InventoryTransaction",
    "Inventory transaction history",
    fields[ResolverContext, InventoryTransactionsRecord](
      Field("id", StringType, description = Some("Unique identifier"), resolve = _.value.id),
      Field("inventoryId", StringType, description = Some("Inventory ID"), resolve = _.value.inventoryId),
      Field(
        "transactionType",
        StringType,
        description = Some("Transaction type (RECEIVED/RESERVED/RELEASED/ISSUED/ADJUSTED)"),
        resolve = _.value.transactionType
      ),
      Field("quantity", BigDecimalType, description = Some("Transaction quantity"), resolve = _.value.quantity),
      Field(
        "fromWarehouseZoneId",
        OptionType(StringType),
        description = Some("Source warehouse zone ID for transfers"),
        resolve = _.value.fromWarehouseZoneId
      ),
      Field(
        "toWarehouseZoneId",
        OptionType(StringType),
        description = Some("Destination warehouse zone ID for transfers"),
        resolve = _.value.toWarehouseZoneId
      ),
      Field(
        "reason",
        OptionType(StringType),
        description = Some("Reason for transaction"),
        resolve = _.value.reason
      ),
      Field(
        "occurredAt",
        OffsetDateTimeType,
        description = Some("Transaction timestamp"),
        resolve = t => OffsetDateTime.ofInstant(t.value.occurredAt.toInstant, java.time.ZoneOffset.UTC)
      ),
      Field(
        "createdAt",
        OffsetDateTimeType,
        description = Some("Creation timestamp"),
        resolve = t => OffsetDateTime.ofInstant(t.value.createdAt.toInstant, java.time.ZoneOffset.UTC)
      )
    )
  )
}
