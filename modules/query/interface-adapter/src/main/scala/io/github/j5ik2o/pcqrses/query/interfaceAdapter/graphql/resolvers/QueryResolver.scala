package io.github.j5ik2o.pcqrses.query.interfaceAdapter.graphql.resolvers

import io.github.j5ik2o.pcqrses.query.interfaceAdapter.dao.*
import io.github.j5ik2o.pcqrses.query.interfaceAdapter.graphql.ResolverContext
import io.github.j5ik2o.pcqrses.query.interfaceAdapter.graphql.errors.{QueryError, ValidationError}
import io.github.j5ik2o.pcqrses.query.interfaceAdapter.graphql.schema.{TypeDefinitions, InventoryTypeDefinitions}
import io.github.j5ik2o.pcqrses.query.interfaceAdapter.graphql.validators.QueryInputValidator
import sangria.schema.*

trait QueryResolver extends TypeDefinitions with InventoryTypeDefinitions {
  this: UserAccountsComponent
    & ProductsComponent
    & InventoriesComponent
    & CustomersComponent
    & WarehousesComponent
    & WarehouseZonesComponent
    & InventoryTransactionsComponent =>

  val QueryType: ObjectType[ResolverContext, Unit] = ObjectType(
    "Query",
    "Root query type",
    fields[ResolverContext, Unit](
      Field(
        "getUserAccount",
        OptionType(UserAccountType),
        description = Some("Get a single user account by ID"),
        arguments = UserAccountIdArg :: Nil,
        resolve = ctx => {
          val id = ctx.arg(UserAccountIdArg)
          QueryInputValidator.validateUserAccountId(id).toEither match {
            case Left(errors) =>
              scala.concurrent.Future.failed(ValidationError(errors.toList))
            case Right(validId) =>
              ctx.ctx
                .runDbAction(UserAccountsDao.findById(validId))
                .recover { case ex: Exception =>
                  throw QueryError(
                    s"Failed to fetch user account: ${ex.getMessage}",
                    Some("FETCH_USER_FAILED"))
                }(ctx.ctx.ec)
          }
        }
      ),
      Field(
        "getUserAccounts",
        ListType(UserAccountType),
        description = Some("Get all user accounts"),
        resolve = ctx => ctx.ctx.runDbAction(UserAccountsDao.findAll())
      ),
      Field(
        "getUserAccountsByIds",
        ListType(UserAccountType),
        description = Some("Get multiple user accounts by IDs"),
        arguments = UserAccountIdsArg :: Nil,
        resolve = ctx => {
          val ids = ctx.arg(UserAccountIdsArg).asInstanceOf[Seq[String]]
          QueryInputValidator.validateUserAccountIds(ids).toEither match {
            case Left(errors) =>
              scala.concurrent.Future.failed(ValidationError(errors.toList))
            case Right(validIds) =>
              ctx.ctx
                .runDbAction(UserAccountsDao.findByIds(validIds))
                .recover { case ex: Exception =>
                  throw QueryError(
                    s"Failed to fetch user accounts: ${ex.getMessage}",
                    Some("FETCH_USERS_FAILED"))
                }(ctx.ctx.ec)
          }
        }
      ),
      Field(
        "searchUserAccounts",
        ListType(UserAccountType),
        description = Some("Search user accounts by name"),
        arguments = Argument("searchTerm", StringType, description = "Search term for name") :: Nil,
        resolve = ctx => {
          val searchTerm = ctx.arg[String]("searchTerm")
          QueryInputValidator.validateSearchTerm(searchTerm).toEither match {
            case Left(errors) =>
              scala.concurrent.Future.failed(ValidationError(errors.toList))
            case Right(validSearchTerm) =>
              ctx.ctx
                .runDbAction {
                  import profile.api._
                  UserAccountsDao
                    .filter(u =>
                      u.firstName.toLowerCase.like(s"%${validSearchTerm.toLowerCase}%") ||
                        u.lastName.toLowerCase.like(s"%${validSearchTerm.toLowerCase}%"))
                    .result
                }
                .recover { case ex: Exception =>
                  throw QueryError(
                    s"Failed to search user accounts: ${ex.getMessage}",
                    Some("SEARCH_USERS_FAILED"))
                }(ctx.ctx.ec)
          }
        }
      ),
      // ========== Product Queries ==========
      Field(
        "getProduct",
        OptionType(ProductType),
        description = Some("Get a single product by ID"),
        arguments = ProductIdArg :: Nil,
        resolve = ctx => {
          val id = ctx.arg(ProductIdArg)
          ctx.ctx.runDbAction {
            import profile.api._
            ProductsDao.filter(_.id === id).result.headOption
          }
        }
      ),
      Field(
        "getProducts",
        ListType(ProductType),
        description = Some("Get all products"),
        resolve = ctx =>
          ctx.ctx.runDbAction {
            import profile.api._
            ProductsDao.result
          }
      ),
      Field(
        "getProductsByIds",
        ListType(ProductType),
        description = Some("Get multiple products by IDs"),
        arguments = ProductIdsArg :: Nil,
        resolve = ctx => {
          val ids = ctx.arg(ProductIdsArg).asInstanceOf[Seq[String]]
          ctx.ctx.runDbAction {
            import profile.api._
            ProductsDao.filter(_.id.inSet(ids)).result
          }
        }
      ),
      Field(
        "getProductByCode",
        OptionType(ProductType),
        description = Some("Get product by product code"),
        arguments = ProductCodeArg :: Nil,
        resolve = ctx => {
          val code = ctx.arg(ProductCodeArg)
          ctx.ctx.runDbAction {
            import profile.api._
            ProductsDao.filter(_.productCode === code).result.headOption
          }
        }
      ),
      Field(
        "getProductsByCategory",
        ListType(ProductType),
        description = Some("Get products by category code"),
        arguments = CategoryCodeArg :: Nil,
        resolve = ctx => {
          val categoryCode = ctx.arg(CategoryCodeArg)
          ctx.ctx.runDbAction {
            import profile.api._
            ProductsDao.filter(_.categoryCode === categoryCode).result
          }
        }
      ),
      Field(
        "getProductsByStorageCondition",
        ListType(ProductType),
        description = Some("Get products by storage condition"),
        arguments = StorageConditionArg :: Nil,
        resolve = ctx => {
          val storageCondition = ctx.arg(StorageConditionArg)
          ctx.ctx.runDbAction {
            import profile.api._
            ProductsDao.filter(_.storageCondition === storageCondition).result
          }
        }
      ),
      // ========== Inventory Queries ==========
      Field(
        "getInventory",
        OptionType(InventoryType),
        description = Some("Get a single inventory by ID"),
        arguments = InventoryIdArg :: Nil,
        resolve = ctx => {
          val id = ctx.arg(InventoryIdArg)
          ctx.ctx.runDbAction {
            import profile.api._
            InventoriesDao.filter(_.id === id).result.headOption
          }
        }
      ),
      Field(
        "getInventories",
        ListType(InventoryType),
        description = Some("Get all inventories"),
        resolve = ctx =>
          ctx.ctx.runDbAction {
            import profile.api._
            InventoriesDao.result
          }
      ),
      Field(
        "getInventoriesByProduct",
        ListType(InventoryType),
        description = Some("Get inventories by product ID"),
        arguments = ProductIdArg :: Nil,
        resolve = ctx => {
          val productId = ctx.arg(ProductIdArg)
          ctx.ctx.runDbAction {
            import profile.api._
            InventoriesDao.filter(_.productId === productId).result
          }
        }
      ),
      Field(
        "getInventoriesByWarehouseZone",
        ListType(InventoryType),
        description = Some("Get inventories by warehouse zone ID"),
        arguments = WarehouseZoneIdArg :: Nil,
        resolve = ctx => {
          val warehouseZoneId = ctx.arg(WarehouseZoneIdArg)
          ctx.ctx.runDbAction {
            import profile.api._
            InventoriesDao.filter(_.warehouseZoneId === warehouseZoneId).result
          }
        }
      ),
      Field(
        "getInventoryTransactions",
        ListType(InventoryTransactionType),
        description = Some("Get inventory transaction history"),
        arguments = InventoryIdArg :: Nil,
        resolve = ctx => {
          val inventoryId = ctx.arg(InventoryIdArg)
          ctx.ctx.runDbAction {
            import profile.api._
            InventoryTransactionsDao.filter(_.inventoryId === inventoryId).sortBy(_.occurredAt.desc).result
          }
        }
      ),
      // ========== Customer Queries ==========
      Field(
        "getCustomer",
        OptionType(CustomerType),
        description = Some("Get a single customer by ID"),
        arguments = CustomerIdArg :: Nil,
        resolve = ctx => {
          val id = ctx.arg(CustomerIdArg)
          ctx.ctx.runDbAction {
            import profile.api._
            CustomersDao.filter(_.id === id).result.headOption
          }
        }
      ),
      Field(
        "getCustomers",
        ListType(CustomerType),
        description = Some("Get all customers"),
        resolve = ctx =>
          ctx.ctx.runDbAction {
            import profile.api._
            CustomersDao.result
          }
      ),
      Field(
        "getCustomerByCode",
        OptionType(CustomerType),
        description = Some("Get customer by customer code"),
        arguments = CustomerCodeArg :: Nil,
        resolve = ctx => {
          val code = ctx.arg(CustomerCodeArg)
          ctx.ctx.runDbAction {
            import profile.api._
            CustomersDao.filter(_.customerCode === code).result.headOption
          }
        }
      ),
      Field(
        "getActiveCustomers",
        ListType(CustomerType),
        description = Some("Get all active customers"),
        resolve = ctx =>
          ctx.ctx.runDbAction {
            import profile.api._
            CustomersDao.filter(_.isActive === true).result
          }
      ),
      // ========== Warehouse Queries ==========
      Field(
        "getWarehouse",
        OptionType(WarehouseType),
        description = Some("Get a single warehouse by ID"),
        arguments = WarehouseIdArg :: Nil,
        resolve = ctx => {
          val id = ctx.arg(WarehouseIdArg)
          ctx.ctx.runDbAction {
            import profile.api._
            WarehousesDao.filter(_.id === id).result.headOption
          }
        }
      ),
      Field(
        "getWarehouses",
        ListType(WarehouseType),
        description = Some("Get all warehouses"),
        resolve = ctx =>
          ctx.ctx.runDbAction {
            import profile.api._
            WarehousesDao.result
          }
      ),
      Field(
        "getWarehouseByCode",
        OptionType(WarehouseType),
        description = Some("Get warehouse by warehouse code"),
        arguments = WarehouseCodeArg :: Nil,
        resolve = ctx => {
          val code = ctx.arg(WarehouseCodeArg)
          ctx.ctx.runDbAction {
            import profile.api._
            WarehousesDao.filter(_.warehouseCode === code).result.headOption
          }
        }
      ),
      Field(
        "getActiveWarehouses",
        ListType(WarehouseType),
        description = Some("Get all active warehouses"),
        resolve = ctx =>
          ctx.ctx.runDbAction {
            import profile.api._
            WarehousesDao.filter(_.isActive === true).result
          }
      ),
      // ========== WarehouseZone Queries ==========
      Field(
        "getWarehouseZone",
        OptionType(WarehouseZoneType),
        description = Some("Get a single warehouse zone by ID"),
        arguments = WarehouseZoneIdArg :: Nil,
        resolve = ctx => {
          val id = ctx.arg(WarehouseZoneIdArg)
          ctx.ctx.runDbAction {
            import profile.api._
            WarehouseZonesDao.filter(_.id === id).result.headOption
          }
        }
      ),
      Field(
        "getWarehouseZones",
        ListType(WarehouseZoneType),
        description = Some("Get all warehouse zones"),
        resolve = ctx =>
          ctx.ctx.runDbAction {
            import profile.api._
            WarehouseZonesDao.result
          }
      ),
      Field(
        "getWarehouseZonesByWarehouse",
        ListType(WarehouseZoneType),
        description = Some("Get warehouse zones by warehouse ID"),
        arguments = WarehouseIdArg :: Nil,
        resolve = ctx => {
          val warehouseId = ctx.arg(WarehouseIdArg)
          ctx.ctx.runDbAction {
            import profile.api._
            WarehouseZonesDao.filter(_.warehouseId === warehouseId).result
          }
        }
      ),
      Field(
        "getWarehouseZonesByZoneType",
        ListType(WarehouseZoneType),
        description = Some("Get warehouse zones by zone type"),
        arguments = Argument("zoneType", StringType, description = "Zone Type") :: Nil,
        resolve = ctx => {
          val zoneType = ctx.arg[String]("zoneType")
          ctx.ctx.runDbAction {
            import profile.api._
            WarehouseZonesDao.filter(_.zoneType === zoneType).result
          }
        }
      ),
      Field(
        "getActiveWarehouseZones",
        ListType(WarehouseZoneType),
        description = Some("Get all active warehouse zones"),
        resolve = ctx =>
          ctx.ctx.runDbAction {
            import profile.api._
            WarehouseZonesDao.filter(_.isActive === true).result
          }
      )
    )
  )
}
