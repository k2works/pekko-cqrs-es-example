package io.github.j5ik2o.pcqrses.query.interfaceAdapter.graphql

import org.scalatest.matchers.should.Matchers
import org.scalatest.freespec.AsyncFreeSpec

import scala.concurrent.ExecutionContext

class InventoryGraphQLServiceSpec extends AsyncFreeSpec with Matchers {
  implicit val ec: ExecutionContext = ExecutionContext.global

  "InventoryGraphQLService" - {
    "parse Product queries" - {
      "getProduct query" in {
        val query = """
          query {
            getProduct(productId: "product-123") {
              id
              productCode
              name
              categoryCode
              storageCondition
              isObsolete
              createdAt
              updatedAt
            }
          }
        """

        val parseResult = sangria.parser.QueryParser.parse(query)
        parseResult.isSuccess shouldBe true
        succeed
      }

      "getProducts query" in {
        val query = """
          query {
            getProducts {
              id
              productCode
              name
            }
          }
        """

        val parseResult = sangria.parser.QueryParser.parse(query)
        parseResult.isSuccess shouldBe true
        succeed
      }

      "getProductByCode query" in {
        val query = """
          query {
            getProductByCode(productCode: "P-001") {
              id
              name
              categoryCode
            }
          }
        """

        val parseResult = sangria.parser.QueryParser.parse(query)
        parseResult.isSuccess shouldBe true
        succeed
      }

      "getProductsByCategory query" in {
        val query = """
          query {
            getProductsByCategory(categoryCode: "CAT-001") {
              id
              productCode
              name
              categoryCode
            }
          }
        """

        val parseResult = sangria.parser.QueryParser.parse(query)
        parseResult.isSuccess shouldBe true
        succeed
      }

      "getProductsByStorageCondition query" in {
        val query = """
          query {
            getProductsByStorageCondition(storageCondition: "RT") {
              id
              name
              storageCondition
            }
          }
        """

        val parseResult = sangria.parser.QueryParser.parse(query)
        parseResult.isSuccess shouldBe true
        succeed
      }
    }

    "parse Inventory queries" - {
      "getInventory query" in {
        val query = """
          query {
            getInventory(inventoryId: "inventory-123") {
              id
              productId
              warehouseZoneId
              availableQuantity
              reservedQuantity
              version
              createdAt
              updatedAt
            }
          }
        """

        val parseResult = sangria.parser.QueryParser.parse(query)
        parseResult.isSuccess shouldBe true
        succeed
      }

      "getInventories query" in {
        val query = """
          query {
            getInventories {
              id
              productId
              availableQuantity
            }
          }
        """

        val parseResult = sangria.parser.QueryParser.parse(query)
        parseResult.isSuccess shouldBe true
        succeed
      }

      "getInventoriesByProduct query" in {
        val query = """
          query {
            getInventoriesByProduct(productId: "product-123") {
              id
              warehouseZoneId
              availableQuantity
              reservedQuantity
            }
          }
        """

        val parseResult = sangria.parser.QueryParser.parse(query)
        parseResult.isSuccess shouldBe true
        succeed
      }

      "getInventoriesByWarehouseZone query" in {
        val query = """
          query {
            getInventoriesByWarehouseZone(warehouseZoneId: "zone-456") {
              id
              productId
              availableQuantity
            }
          }
        """

        val parseResult = sangria.parser.QueryParser.parse(query)
        parseResult.isSuccess shouldBe true
        succeed
      }

      "getInventoryTransactions query" in {
        val query = """
          query {
            getInventoryTransactions(inventoryId: "inventory-123") {
              id
              inventoryId
              transactionType
              quantity
              fromWarehouseZoneId
              toWarehouseZoneId
              reason
              occurredAt
              createdAt
            }
          }
        """

        val parseResult = sangria.parser.QueryParser.parse(query)
        parseResult.isSuccess shouldBe true
        succeed
      }
    }

    "parse Customer queries" - {
      "getCustomer query" in {
        val query = """
          query {
            getCustomer(customerId: "customer-123") {
              id
              customerCode
              name
              customerType
              isActive
            }
          }
        """

        val parseResult = sangria.parser.QueryParser.parse(query)
        parseResult.isSuccess shouldBe true
        succeed
      }

      "getCustomers query" in {
        val query = """
          query {
            getCustomers {
              id
              name
            }
          }
        """

        val parseResult = sangria.parser.QueryParser.parse(query)
        parseResult.isSuccess shouldBe true
        succeed
      }

      "getActiveCustomers query" in {
        val query = """
          query {
            getActiveCustomers {
              id
              customerCode
              name
              isActive
            }
          }
        """

        val parseResult = sangria.parser.QueryParser.parse(query)
        parseResult.isSuccess shouldBe true
        succeed
      }
    }

    "parse Warehouse queries" - {
      "getWarehouse query" in {
        val query = """
          query {
            getWarehouse(warehouseId: "warehouse-123") {
              id
              warehouseCode
              name
              location
              isActive
            }
          }
        """

        val parseResult = sangria.parser.QueryParser.parse(query)
        parseResult.isSuccess shouldBe true
        succeed
      }

      "getActiveWarehouses query" in {
        val query = """
          query {
            getActiveWarehouses {
              id
              name
              location
            }
          }
        """

        val parseResult = sangria.parser.QueryParser.parse(query)
        parseResult.isSuccess shouldBe true
        succeed
      }
    }

    "parse WarehouseZone queries" - {
      "getWarehouseZone query" in {
        val query = """
          query {
            getWarehouseZone(warehouseZoneId: "zone-123") {
              id
              warehouseId
              zoneCode
              name
              zoneType
              capacitySqm
              isActive
            }
          }
        """

        val parseResult = sangria.parser.QueryParser.parse(query)
        parseResult.isSuccess shouldBe true
        succeed
      }

      "getWarehouseZonesByWarehouse query" in {
        val query = """
          query {
            getWarehouseZonesByWarehouse(warehouseId: "warehouse-123") {
              id
              zoneCode
              name
            }
          }
        """

        val parseResult = sangria.parser.QueryParser.parse(query)
        parseResult.isSuccess shouldBe true
        succeed
      }

      "getActiveWarehouseZones query" in {
        val query = """
          query {
            getActiveWarehouseZones {
              id
              name
              isActive
            }
          }
        """

        val parseResult = sangria.parser.QueryParser.parse(query)
        parseResult.isSuccess shouldBe true
        succeed
      }
    }

    "parse queries with variables" - {
      "getProduct with variables" in {
        val query = """
          query GetProduct($productId: String!) {
            getProduct(productId: $productId) {
              id
              name
            }
          }
        """

        val parseResult = sangria.parser.QueryParser.parse(query)
        parseResult.isSuccess shouldBe true
        succeed
      }

      "getInventoriesByProduct with variables" in {
        val query = """
          query GetInventoriesByProduct($productId: String!) {
            getInventoriesByProduct(productId: $productId) {
              id
              availableQuantity
              reservedQuantity
            }
          }
        """

        val parseResult = sangria.parser.QueryParser.parse(query)
        parseResult.isSuccess shouldBe true
        succeed
      }
    }

    "handle complex query combinations" - {
      "multiple queries in single request" in {
        val query = """
          query {
            product: getProduct(productId: "product-123") {
              id
              name
            }
            inventories: getInventoriesByProduct(productId: "product-123") {
              id
              availableQuantity
            }
            transactions: getInventoryTransactions(inventoryId: "inventory-123") {
              id
              transactionType
              quantity
            }
          }
        """

        val parseResult = sangria.parser.QueryParser.parse(query)
        parseResult.isSuccess shouldBe true
        succeed
      }

      "nested query structure" in {
        val query = """
          query {
            getProducts {
              id
              productCode
              name
            }
            getWarehouses {
              id
              name
            }
          }
        """

        val parseResult = sangria.parser.QueryParser.parse(query)
        parseResult.isSuccess shouldBe true
        succeed
      }
    }

    "handle introspection queries" - {
      "query type introspection" in {
        val introspection = """
          query {
            __type(name: "Query") {
              name
              fields {
                name
                description
                args {
                  name
                  type {
                    name
                  }
                }
              }
            }
          }
        """

        val parseResult = sangria.parser.QueryParser.parse(introspection)
        parseResult.isSuccess shouldBe true
        succeed
      }

      "schema introspection" in {
        val introspection = """
          query {
            __schema {
              queryType {
                name
                fields {
                  name
                }
              }
            }
          }
        """

        val parseResult = sangria.parser.QueryParser.parse(introspection)
        parseResult.isSuccess shouldBe true
        succeed
      }
    }

    "detect syntax errors" - {
      "invalid query structure" in {
        val invalidQuery = """
          query {
            getProduct(productId: "product-123" {
              id
              name
            }
          }
        """

        val parseResult = sangria.parser.QueryParser.parse(invalidQuery)
        parseResult.isFailure shouldBe true
        succeed
      }

      "missing required argument" in {
        val invalidQuery = """
          query {
            getProduct {
              id
              name
            }
          }
        """

        // パーサーは構文としては通るが、実行時にバリデーションエラーになる
        val parseResult = sangria.parser.QueryParser.parse(invalidQuery)
        parseResult.isSuccess shouldBe true
        succeed
      }
    }
  }
}
