package io.github.j5ik2o.pcqrses.command.interfaceAdapter.graphql

import org.scalatest.matchers.should.Matchers
import org.scalatest.freespec.AsyncFreeSpec

import scala.concurrent.ExecutionContext

class InventoryGraphQLServiceSpec extends AsyncFreeSpec with Matchers {
  implicit val ec: ExecutionContext = ExecutionContext.global

  "InventoryGraphQLService" - {
    "parse Product mutations" - {
      "createProduct mutation" in {
        val mutation = """
          mutation {
            createProduct(input: {
              productCode: "P-001"
              name: "Test Product"
              categoryCode: "CAT-001"
              storageCondition: "RT"
            }) {
              id
            }
          }
        """

        val parseResult = sangria.parser.QueryParser.parse(mutation)
        parseResult.isSuccess shouldBe true
        succeed
      }

      "updateProduct mutation" in {
        val mutation = """
          mutation {
            updateProduct(input: {
              id: "product-123"
              name: "Updated Product"
              categoryCode: "CAT-002"
              storageCondition: "RF"
            }) {
              id
            }
          }
        """

        val parseResult = sangria.parser.QueryParser.parse(mutation)
        parseResult.isSuccess shouldBe true
        succeed
      }

      "obsoleteProduct mutation" in {
        val mutation = """
          mutation {
            obsoleteProduct(input: {
              id: "product-123"
            }) {
              id
            }
          }
        """

        val parseResult = sangria.parser.QueryParser.parse(mutation)
        parseResult.isSuccess shouldBe true
        succeed
      }
    }

    "parse Inventory mutations" - {
      "createInventory mutation" in {
        val mutation = """
          mutation {
            createInventory(input: {
              productId: "product-123"
              warehouseZoneId: "zone-456"
            }) {
              id
            }
          }
        """

        val parseResult = sangria.parser.QueryParser.parse(mutation)
        parseResult.isSuccess shouldBe true
        succeed
      }

      "receiveInventory mutation" in {
        val mutation = """
          mutation {
            receiveInventory(input: {
              id: "inventory-123"
              quantity: 100.0
              expectedVersion: 0
            }) {
              version
            }
          }
        """

        val parseResult = sangria.parser.QueryParser.parse(mutation)
        parseResult.isSuccess shouldBe true
        succeed
      }

      "reserveInventory mutation" in {
        val mutation = """
          mutation {
            reserveInventory(input: {
              id: "inventory-123"
              quantity: 50.0
              expectedVersion: 1
            }) {
              version
            }
          }
        """

        val parseResult = sangria.parser.QueryParser.parse(mutation)
        parseResult.isSuccess shouldBe true
        succeed
      }

      "releaseInventory mutation" in {
        val mutation = """
          mutation {
            releaseInventory(input: {
              id: "inventory-123"
              quantity: 30.0
              expectedVersion: 2
            }) {
              version
            }
          }
        """

        val parseResult = sangria.parser.QueryParser.parse(mutation)
        parseResult.isSuccess shouldBe true
        succeed
      }

      "issueInventory mutation" in {
        val mutation = """
          mutation {
            issueInventory(input: {
              id: "inventory-123"
              quantity: 20.0
              expectedVersion: 3
            }) {
              version
            }
          }
        """

        val parseResult = sangria.parser.QueryParser.parse(mutation)
        parseResult.isSuccess shouldBe true
        succeed
      }

      "adjustInventory mutation" in {
        val mutation = """
          mutation {
            adjustInventory(input: {
              id: "inventory-123"
              newQuantity: 95.0
              reason: "棚卸調整"
              expectedVersion: 4
            }) {
              version
            }
          }
        """

        val parseResult = sangria.parser.QueryParser.parse(mutation)
        parseResult.isSuccess shouldBe true
        succeed
      }
    }

    "parse Customer mutations" - {
      "createCustomer mutation" in {
        val mutation = """
          mutation {
            createCustomer(input: {
              customerCode: "C-001"
              name: "Test Customer"
              email: "test@example.com"
              phone: "03-1234-5678"
              address: "Tokyo"
            }) {
              id
            }
          }
        """

        val parseResult = sangria.parser.QueryParser.parse(mutation)
        parseResult.isSuccess shouldBe true
        succeed
      }

      "deactivateCustomer mutation" in {
        val mutation = """
          mutation {
            deactivateCustomer(input: {
              id: "customer-123"
            }) {
              id
            }
          }
        """

        val parseResult = sangria.parser.QueryParser.parse(mutation)
        parseResult.isSuccess shouldBe true
        succeed
      }
    }

    "parse Warehouse mutations" - {
      "createWarehouse mutation" in {
        val mutation = """
          mutation {
            createWarehouse(input: {
              warehouseCode: "W-001"
              name: "Main Warehouse"
              address: "Tokyo"
            }) {
              id
            }
          }
        """

        val parseResult = sangria.parser.QueryParser.parse(mutation)
        parseResult.isSuccess shouldBe true
        succeed
      }
    }

    "parse WarehouseZone mutations" - {
      "createWarehouseZone mutation" in {
        val mutation = """
          mutation {
            createWarehouseZone(input: {
              warehouseId: "warehouse-123"
              zoneCode: "Z-001"
              name: "Zone A"
              storageCondition: "RT"
            }) {
              id
            }
          }
        """

        val parseResult = sangria.parser.QueryParser.parse(mutation)
        parseResult.isSuccess shouldBe true
        succeed
      }
    }

    "parse mutations with variables" - {
      "createProduct with variables" in {
        val mutation = """
          mutation CreateProduct($input: CreateProductInput!) {
            createProduct(input: $input) {
              id
            }
          }
        """

        val parseResult = sangria.parser.QueryParser.parse(mutation)
        parseResult.isSuccess shouldBe true
        succeed
      }

      "receiveInventory with variables" in {
        val mutation = """
          mutation ReceiveInventory($input: ReceiveInventoryInput!) {
            receiveInventory(input: $input) {
              version
            }
          }
        """

        val parseResult = sangria.parser.QueryParser.parse(mutation)
        parseResult.isSuccess shouldBe true
        succeed
      }
    }

    "handle complex mutation chains" - {
      "multiple operations in single mutation" in {
        val mutation = """
          mutation {
            prod: createProduct(input: {
              productCode: "P-001"
              name: "Product 1"
              categoryCode: "CAT-001"
              storageCondition: "RT"
            }) {
              id
            }
            inv: createInventory(input: {
              productId: "product-123"
              warehouseZoneId: "zone-456"
            }) {
              id
            }
          }
        """

        val parseResult = sangria.parser.QueryParser.parse(mutation)
        parseResult.isSuccess shouldBe true
        succeed
      }
    }

    "handle introspection queries for mutations" - {
      "query mutation type" in {
        val introspection = """
          query {
            __type(name: "Mutation") {
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
    }

    "detect syntax errors" - {
      "invalid input structure" in {
        val invalidMutation = """
          mutation {
            createProduct(input: {
              productCode: "P-001"
              // missing closing brace
            id
          }
        """

        val parseResult = sangria.parser.QueryParser.parse(invalidMutation)
        parseResult.isFailure shouldBe true
        succeed
      }

      "missing required field" in {
        val invalidMutation = """
          mutation {
            createProduct {
              id
            }
          }
        """

        // パーサーは構文としては通るが、実行時にバリデーションエラーになる
        val parseResult = sangria.parser.QueryParser.parse(invalidMutation)
        parseResult.isSuccess shouldBe true
        succeed
      }
    }
  }
}
