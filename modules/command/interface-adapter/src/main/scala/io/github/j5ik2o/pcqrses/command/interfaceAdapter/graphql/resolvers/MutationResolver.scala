package io.github.j5ik2o.pcqrses.command.interfaceAdapter.graphql.resolvers

import io.github.j5ik2o.pcqrses.command.interfaceAdapter.graphql.ResolverContext
import io.github.j5ik2o.pcqrses.command.interfaceAdapter.graphql.errors.{
  CommandError,
  ValidationError
}
import io.github.j5ik2o.pcqrses.command.interfaceAdapter.graphql.schema.{
  CreateUserAccountResult,
  TypeDefinitions,
  InventoryTypeDefinitions
}
import io.github.j5ik2o.pcqrses.command.interfaceAdapter.graphql.validators.CreateUserAccountInputValidator
import io.github.j5ik2o.pcqrses.command.domain.inventory.*
import sangria.schema.*

/**
 * GraphQL Mutation リゾルバー
 */
trait MutationResolver extends TypeDefinitions with InventoryTypeDefinitions {

  val MutationType: ObjectType[ResolverContext, Unit] = ObjectType(
    "Mutation",
    "Root mutation type",
    fields[ResolverContext, Unit](
      Field(
        "createUserAccount",
        CreateUserAccountResultType,
        description = Some("Create a new user account"),
        arguments = CreateUserAccountInputArg :: Nil,
        resolve = ctx => {
          val input = ctx.arg(CreateUserAccountInputArg)

          CreateUserAccountInputValidator.validate(input).toEither match {
            case Left(errors) =>
              // ValidationErrorを使用してバリデーションエラーを返す
              scala.concurrent.Future.failed(ValidationError(errors.toList))
            case Right((userAccountName, emailAddress)) =>
              ctx.ctx.runZioTask(
                ctx.ctx.userAccountUseCase
                  .createUserAccount(userAccountName, emailAddress)
                  .mapBoth(
                    // CommandErrorを使用してコマンド実行エラーを返す
                    error =>
                      CommandError(
                        s"Failed to create user account: ${error.toString}",
                        Some("CREATE_USER_FAILED")),
                    userAccountId => CreateUserAccountResult(id = userAccountId.asString)
                  )
              )
          }
        }
      ),
      // ========== Product Mutations ==========
      Field(
        "createProduct",
        CreateProductResultType,
        description = Some("Create a new product"),
        arguments = CreateProductInputArg :: Nil,
        resolve = ctx => {
          val input = ctx.arg(CreateProductInputArg)
          ctx.ctx.runZioTask(
            ctx.ctx.productUseCase
              .createProduct(
                ProductCode(input.productCode),
                ProductName(input.name),
                CategoryCode(input.categoryCode),
                StorageCondition.fromString(input.storageCondition).getOrElse(StorageCondition.RT)
              )
              .mapBoth(
                error => CommandError(s"Failed to create product: ${error.toString}", Some("CREATE_PRODUCT_FAILED")),
                productId => CreateProductResult(id = productId.asString)
              )
          )
        }
      ),
      Field(
        "updateProduct",
        UpdateProductResultType,
        description = Some("Update product information"),
        arguments = UpdateProductInputArg :: Nil,
        resolve = ctx => {
          val input = ctx.arg(UpdateProductInputArg)
          ctx.ctx.runZioTask(
            ctx.ctx.productUseCase
              .updateProduct(
                ProductId.from(input.id),
                ProductName(input.name),
                CategoryCode(input.categoryCode),
                StorageCondition.fromString(input.storageCondition).getOrElse(StorageCondition.RT)
              )
              .mapBoth(
                error => CommandError(s"Failed to update product: ${error.toString}", Some("UPDATE_PRODUCT_FAILED")),
                _ => UpdateProductResult(id = input.id)
              )
          )
        }
      ),
      Field(
        "obsoleteProduct",
        ObsoleteProductResultType,
        description = Some("Mark product as obsolete"),
        arguments = ObsoleteProductInputArg :: Nil,
        resolve = ctx => {
          val input = ctx.arg(ObsoleteProductInputArg)
          ctx.ctx.runZioTask(
            ctx.ctx.productUseCase
              .obsoleteProduct(ProductId.from(input.id))
              .mapBoth(
                error => CommandError(s"Failed to obsolete product: ${error.toString}", Some("OBSOLETE_PRODUCT_FAILED")),
                _ => ObsoleteProductResult(id = input.id)
              )
          )
        }
      ),
      // ========== Inventory Mutations ==========
      Field(
        "createInventory",
        CreateInventoryResultType,
        description = Some("Create a new inventory"),
        arguments = CreateInventoryInputArg :: Nil,
        resolve = ctx => {
          val input = ctx.arg(CreateInventoryInputArg)
          ctx.ctx.runZioTask(
            ctx.ctx.inventoryUseCase
              .createInventory(
                ProductId.from(input.productId),
                WarehouseZoneId.from(input.warehouseZoneId)
              )
              .mapBoth(
                error => CommandError(s"Failed to create inventory: ${error.toString}", Some("CREATE_INVENTORY_FAILED")),
                inventoryId => CreateInventoryResult(id = inventoryId.asString)
              )
          )
        }
      ),
      Field(
        "receiveInventory",
        InventoryVersionResultType,
        description = Some("Receive inventory"),
        arguments = ReceiveInventoryInputArg :: Nil,
        resolve = ctx => {
          val input = ctx.arg(ReceiveInventoryInputArg)
          ctx.ctx.runZioTask(
            ctx.ctx.inventoryUseCase
              .receiveInventory(
                InventoryId.from(input.id),
                Quantity(input.quantity),
                Version(input.expectedVersion)
              )
              .mapBoth(
                error => CommandError(s"Failed to receive inventory: ${error.toString}", Some("RECEIVE_INVENTORY_FAILED")),
                newVersion => newVersion.value
              )
          )
        }
      ),
      Field(
        "reserveInventory",
        InventoryVersionResultType,
        description = Some("Reserve inventory"),
        arguments = ReserveInventoryInputArg :: Nil,
        resolve = ctx => {
          val input = ctx.arg(ReserveInventoryInputArg)
          ctx.ctx.runZioTask(
            ctx.ctx.inventoryUseCase
              .reserveInventory(
                InventoryId.from(input.id),
                Quantity(input.quantity),
                Version(input.expectedVersion)
              )
              .mapBoth(
                error => CommandError(s"Failed to reserve inventory: ${error.toString}", Some("RESERVE_INVENTORY_FAILED")),
                newVersion => newVersion.value
              )
          )
        }
      ),
      Field(
        "releaseInventory",
        InventoryVersionResultType,
        description = Some("Release reserved inventory"),
        arguments = ReleaseInventoryInputArg :: Nil,
        resolve = ctx => {
          val input = ctx.arg(ReleaseInventoryInputArg)
          ctx.ctx.runZioTask(
            ctx.ctx.inventoryUseCase
              .releaseInventory(
                InventoryId.from(input.id),
                Quantity(input.quantity),
                Version(input.expectedVersion)
              )
              .mapBoth(
                error => CommandError(s"Failed to release inventory: ${error.toString}", Some("RELEASE_INVENTORY_FAILED")),
                newVersion => newVersion.value
              )
          )
        }
      ),
      Field(
        "issueInventory",
        InventoryVersionResultType,
        description = Some("Issue inventory"),
        arguments = IssueInventoryInputArg :: Nil,
        resolve = ctx => {
          val input = ctx.arg(IssueInventoryInputArg)
          ctx.ctx.runZioTask(
            ctx.ctx.inventoryUseCase
              .issueInventory(
                InventoryId.from(input.id),
                Quantity(input.quantity),
                Version(input.expectedVersion)
              )
              .mapBoth(
                error => CommandError(s"Failed to issue inventory: ${error.toString}", Some("ISSUE_INVENTORY_FAILED")),
                newVersion => newVersion.value
              )
          )
        }
      ),
      Field(
        "adjustInventory",
        InventoryVersionResultType,
        description = Some("Adjust inventory quantity"),
        arguments = AdjustInventoryInputArg :: Nil,
        resolve = ctx => {
          val input = ctx.arg(AdjustInventoryInputArg)
          ctx.ctx.runZioTask(
            ctx.ctx.inventoryUseCase
              .adjustInventory(
                InventoryId.from(input.id),
                Quantity(input.newQuantity),
                AdjustmentReason(input.reason),
                Version(input.expectedVersion)
              )
              .mapBoth(
                error => CommandError(s"Failed to adjust inventory: ${error.toString}", Some("ADJUST_INVENTORY_FAILED")),
                newVersion => newVersion.value
              )
          )
        }
      ),
      // ========== Customer Mutations ==========
      Field(
        "createCustomer",
        CreateCustomerResultType,
        description = Some("Create a new customer"),
        arguments = CreateCustomerInputArg :: Nil,
        resolve = ctx => {
          val input = ctx.arg(CreateCustomerInputArg)
          ctx.ctx.runZioTask(
            ctx.ctx.customerUseCase
              .createCustomer(
                CustomerCode(input.customerCode),
                CustomerName(input.name),
                EmailAddress(input.email),
                PhoneNumber(input.phone),
                Address(input.address)
              )
              .mapBoth(
                error => CommandError(s"Failed to create customer: ${error.toString}", Some("CREATE_CUSTOMER_FAILED")),
                customerId => CreateCustomerResult(id = customerId.asString)
              )
          )
        }
      ),
      Field(
        "updateCustomer",
        UpdateCustomerResultType,
        description = Some("Update customer information"),
        arguments = UpdateCustomerInputArg :: Nil,
        resolve = ctx => {
          val input = ctx.arg(UpdateCustomerInputArg)
          ctx.ctx.runZioTask(
            ctx.ctx.customerUseCase
              .updateCustomer(
                CustomerId.from(input.id),
                CustomerName(input.name),
                EmailAddress(input.email),
                PhoneNumber(input.phone),
                Address(input.address)
              )
              .mapBoth(
                error => CommandError(s"Failed to update customer: ${error.toString}", Some("UPDATE_CUSTOMER_FAILED")),
                _ => UpdateCustomerResult(id = input.id)
              )
          )
        }
      ),
      Field(
        "deactivateCustomer",
        DeactivateCustomerResultType,
        description = Some("Deactivate a customer"),
        arguments = DeactivateCustomerInputArg :: Nil,
        resolve = ctx => {
          val input = ctx.arg(DeactivateCustomerInputArg)
          ctx.ctx.runZioTask(
            ctx.ctx.customerUseCase
              .deactivateCustomer(CustomerId.from(input.id))
              .mapBoth(
                error => CommandError(s"Failed to deactivate customer: ${error.toString}", Some("DEACTIVATE_CUSTOMER_FAILED")),
                _ => DeactivateCustomerResult(id = input.id)
              )
          )
        }
      ),
      Field(
        "reactivateCustomer",
        ReactivateCustomerResultType,
        description = Some("Reactivate a customer"),
        arguments = ReactivateCustomerInputArg :: Nil,
        resolve = ctx => {
          val input = ctx.arg(ReactivateCustomerInputArg)
          ctx.ctx.runZioTask(
            ctx.ctx.customerUseCase
              .reactivateCustomer(CustomerId.from(input.id))
              .mapBoth(
                error => CommandError(s"Failed to reactivate customer: ${error.toString}", Some("REACTIVATE_CUSTOMER_FAILED")),
                _ => ReactivateCustomerResult(id = input.id)
              )
          )
        }
      ),
      // ========== Warehouse Mutations ==========
      Field(
        "createWarehouse",
        CreateWarehouseResultType,
        description = Some("Create a new warehouse"),
        arguments = CreateWarehouseInputArg :: Nil,
        resolve = ctx => {
          val input = ctx.arg(CreateWarehouseInputArg)
          ctx.ctx.runZioTask(
            ctx.ctx.warehouseUseCase
              .createWarehouse(
                WarehouseCode(input.warehouseCode),
                WarehouseName(input.name),
                Address(input.address)
              )
              .mapBoth(
                error => CommandError(s"Failed to create warehouse: ${error.toString}", Some("CREATE_WAREHOUSE_FAILED")),
                warehouseId => CreateWarehouseResult(id = warehouseId.asString)
              )
          )
        }
      ),
      Field(
        "updateWarehouse",
        UpdateWarehouseResultType,
        description = Some("Update warehouse information"),
        arguments = UpdateWarehouseInputArg :: Nil,
        resolve = ctx => {
          val input = ctx.arg(UpdateWarehouseInputArg)
          ctx.ctx.runZioTask(
            ctx.ctx.warehouseUseCase
              .updateWarehouse(
                WarehouseId.from(input.id),
                WarehouseName(input.name),
                Address(input.address)
              )
              .mapBoth(
                error => CommandError(s"Failed to update warehouse: ${error.toString}", Some("UPDATE_WAREHOUSE_FAILED")),
                _ => UpdateWarehouseResult(id = input.id)
              )
          )
        }
      ),
      Field(
        "deactivateWarehouse",
        DeactivateWarehouseResultType,
        description = Some("Deactivate a warehouse"),
        arguments = DeactivateWarehouseInputArg :: Nil,
        resolve = ctx => {
          val input = ctx.arg(DeactivateWarehouseInputArg)
          ctx.ctx.runZioTask(
            ctx.ctx.warehouseUseCase
              .deactivateWarehouse(WarehouseId.from(input.id))
              .mapBoth(
                error => CommandError(s"Failed to deactivate warehouse: ${error.toString}", Some("DEACTIVATE_WAREHOUSE_FAILED")),
                _ => DeactivateWarehouseResult(id = input.id)
              )
          )
        }
      ),
      Field(
        "reactivateWarehouse",
        ReactivateWarehouseResultType,
        description = Some("Reactivate a warehouse"),
        arguments = ReactivateWarehouseInputArg :: Nil,
        resolve = ctx => {
          val input = ctx.arg(ReactivateWarehouseInputArg)
          ctx.ctx.runZioTask(
            ctx.ctx.warehouseUseCase
              .reactivateWarehouse(WarehouseId.from(input.id))
              .mapBoth(
                error => CommandError(s"Failed to reactivate warehouse: ${error.toString}", Some("REACTIVATE_WAREHOUSE_FAILED")),
                _ => ReactivateWarehouseResult(id = input.id)
              )
          )
        }
      ),
      // ========== WarehouseZone Mutations ==========
      Field(
        "createWarehouseZone",
        CreateWarehouseZoneResultType,
        description = Some("Create a new warehouse zone"),
        arguments = CreateWarehouseZoneInputArg :: Nil,
        resolve = ctx => {
          val input = ctx.arg(CreateWarehouseZoneInputArg)
          ctx.ctx.runZioTask(
            ctx.ctx.warehouseZoneUseCase
              .createWarehouseZone(
                WarehouseId.from(input.warehouseId),
                ZoneCode(input.zoneCode),
                ZoneName(input.name),
                StorageCondition.fromString(input.storageCondition).getOrElse(StorageCondition.RT)
              )
              .mapBoth(
                error => CommandError(s"Failed to create warehouse zone: ${error.toString}", Some("CREATE_WAREHOUSE_ZONE_FAILED")),
                warehouseZoneId => CreateWarehouseZoneResult(id = warehouseZoneId.asString)
              )
          )
        }
      ),
      Field(
        "updateWarehouseZone",
        UpdateWarehouseZoneResultType,
        description = Some("Update warehouse zone information"),
        arguments = UpdateWarehouseZoneInputArg :: Nil,
        resolve = ctx => {
          val input = ctx.arg(UpdateWarehouseZoneInputArg)
          ctx.ctx.runZioTask(
            ctx.ctx.warehouseZoneUseCase
              .updateWarehouseZone(
                WarehouseZoneId.from(input.id),
                ZoneName(input.name),
                StorageCondition.fromString(input.storageCondition).getOrElse(StorageCondition.RT)
              )
              .mapBoth(
                error => CommandError(s"Failed to update warehouse zone: ${error.toString}", Some("UPDATE_WAREHOUSE_ZONE_FAILED")),
                _ => UpdateWarehouseZoneResult(id = input.id)
              )
          )
        }
      ),
      Field(
        "deactivateWarehouseZone",
        DeactivateWarehouseZoneResultType,
        description = Some("Deactivate a warehouse zone"),
        arguments = DeactivateWarehouseZoneInputArg :: Nil,
        resolve = ctx => {
          val input = ctx.arg(DeactivateWarehouseZoneInputArg)
          ctx.ctx.runZioTask(
            ctx.ctx.warehouseZoneUseCase
              .deactivateWarehouseZone(WarehouseZoneId.from(input.id))
              .mapBoth(
                error => CommandError(s"Failed to deactivate warehouse zone: ${error.toString}", Some("DEACTIVATE_WAREHOUSE_ZONE_FAILED")),
                _ => DeactivateWarehouseZoneResult(id = input.id)
              )
          )
        }
      ),
      Field(
        "reactivateWarehouseZone",
        ReactivateWarehouseZoneResultType,
        description = Some("Reactivate a warehouse zone"),
        arguments = ReactivateWarehouseZoneInputArg :: Nil,
        resolve = ctx => {
          val input = ctx.arg(ReactivateWarehouseZoneInputArg)
          ctx.ctx.runZioTask(
            ctx.ctx.warehouseZoneUseCase
              .reactivateWarehouseZone(WarehouseZoneId.from(input.id))
              .mapBoth(
                error => CommandError(s"Failed to reactivate warehouse zone: ${error.toString}", Some("REACTIVATE_WAREHOUSE_ZONE_FAILED")),
                _ => ReactivateWarehouseZoneResult(id = input.id)
              )
          )
        }
      )
    )
  )
}
