package io.github.j5ik2o.pcqrses.command.interfaceAdapter.aggregate.inventory

import com.typesafe.config.{Config, ConfigFactory}
import io.github.j5ik2o.pcqrses.command.domain.inventory.*
import io.github.j5ik2o.pcqrses.command.interfaceAdapter.contract.inventory.ProductProtocol
import io.github.j5ik2o.pcqrses.command.interfaceAdapter.test.ActorSpec
import org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers

import java.nio.file.Files
import java.util.{Comparator, UUID}

object ProductAggregateSpec {
  val id: String = UUID.randomUUID().toString

  val config: Config = ConfigFactory
    .parseString(
      s"""
       |pekko {
       |  persistence {
       |    journal {
       |      plugin = "pekko.persistence.journal.inmem"
       |      inmem {
       |        class = "org.apache.pekko.persistence.journal.inmem.InmemJournal"
       |        plugin-dispatcher = "pekko.actor.default-dispatcher"
       |      }
       |    }
       |    snapshot-store {
       |      plugin = "pekko.persistence.snapshot-store.local"
       |      local {
       |        dir = "target/snapshot/$id"
       |      }
       |    }
       |  }
       |  test {
       |    single-expect-default = 5s
       |  }
       |}
       |""".stripMargin
    )
    .withFallback(ConfigFactory.load())
}

class ProductAggregateSpec
  extends ActorSpec(ProductAggregateSpec.config)
  with Matchers
  with Eventually
  with BeforeAndAfterAll {

  override def afterAll(): Unit = {
    super.afterAll()
    val snapshotDir = new java.io.File(s"target/snapshot/${ProductAggregateSpec.id}")
    if (snapshotDir.exists()) {
      Files
        .walk(snapshotDir.toPath)
        .sorted(Comparator.reverseOrder())
        .forEach(Files.delete(_))
    }
  }

  "ProductAggregate" - {
    "商品が未作成の状態" - {
      "Createコマンドを受信したとき" - {
        "新しい商品を作成できる" in {
          val productId = ProductId.generate()
          val probe = TestProbe[ProductProtocol.CreateProductReply]()
          val aggregate = spawn(ProductAggregate(productId))

          aggregate ! ProductProtocol.CreateProduct(
            productId,
            ProductCode("TEST-001"),
            ProductName("テスト商品"),
            CategoryCode("CAT-001"),
            StorageCondition.RoomTemperature,
            probe.ref
          )

          val reply = probe.receiveMessage()
          reply shouldBe a[ProductProtocol.CreateProductSucceeded]
          val succeeded = reply.asInstanceOf[ProductProtocol.CreateProductSucceeded]
          succeeded.id shouldBe productId
        }
      }

      "Updateコマンドを受信したとき" - {
        "NotFoundエラーを返す" in {
          val productId = ProductId.generate()
          val probe = TestProbe[ProductProtocol.UpdateProductReply]()
          val aggregate = spawn(ProductAggregate(productId))

          aggregate ! ProductProtocol.UpdateProduct(
            productId,
            ProductName("更新商品"),
            CategoryCode("CAT-002"),
            StorageCondition.Refrigerated,
            probe.ref
          )

          val reply = probe.receiveMessage()
          reply shouldBe a[ProductProtocol.UpdateProductFailed]
        }
      }
    }

    "商品が作成済みの状態" - {
      "Updateコマンドを受信したとき" - {
        "商品情報を更新できる" in {
          val productId = ProductId.generate()
          val createProbe = TestProbe[ProductProtocol.CreateProductReply]()
          val updateProbe = TestProbe[ProductProtocol.UpdateProductReply]()
          val aggregate = spawn(ProductAggregate(productId))

          // 商品作成
          aggregate ! ProductProtocol.CreateProduct(
            productId,
            ProductCode("TEST-002"),
            ProductName("テスト商品2"),
            CategoryCode("CAT-001"),
            StorageCondition.RoomTemperature,
            createProbe.ref
          )
          createProbe.receiveMessage() shouldBe a[ProductProtocol.CreateProductSucceeded]

          // 商品更新
          aggregate ! ProductProtocol.UpdateProduct(
            productId,
            ProductName("更新された商品2"),
            CategoryCode("CAT-003"),
            StorageCondition.Frozen,
            updateProbe.ref
          )

          val reply = updateProbe.receiveMessage()
          reply shouldBe a[ProductProtocol.UpdateProductSucceeded]
        }
      }

      "Obsoleteコマンドを受信したとき" - {
        "商品を廃止できる" in {
          val productId = ProductId.generate()
          val createProbe = TestProbe[ProductProtocol.CreateProductReply]()
          val obsoleteProbe = TestProbe[ProductProtocol.ObsoleteProductReply]()
          val aggregate = spawn(ProductAggregate(productId))

          // 商品作成
          aggregate ! ProductProtocol.CreateProduct(
            productId,
            ProductCode("TEST-003"),
            ProductName("テスト商品3"),
            CategoryCode("CAT-001"),
            StorageCondition.RoomTemperature,
            createProbe.ref
          )
          createProbe.receiveMessage() shouldBe a[ProductProtocol.CreateProductSucceeded]

          // 商品廃止
          aggregate ! ProductProtocol.ObsoleteProduct(productId, obsoleteProbe.ref)

          val reply = obsoleteProbe.receiveMessage()
          reply shouldBe a[ProductProtocol.ObsoleteProductSucceeded]
        }
      }
    }

    "商品が廃止済みの状態" - {
      "Updateコマンドを受信したとき" - {
        "エラーを返す" in {
          val productId = ProductId.generate()
          val createProbe = TestProbe[ProductProtocol.CreateProductReply]()
          val obsoleteProbe = TestProbe[ProductProtocol.ObsoleteProductReply]()
          val updateProbe = TestProbe[ProductProtocol.UpdateProductReply]()
          val aggregate = spawn(ProductAggregate(productId))

          // 商品作成
          aggregate ! ProductProtocol.CreateProduct(
            productId,
            ProductCode("TEST-004"),
            ProductName("テスト商品4"),
            CategoryCode("CAT-001"),
            StorageCondition.RoomTemperature,
            createProbe.ref
          )
          createProbe.receiveMessage() shouldBe a[ProductProtocol.CreateProductSucceeded]

          // 商品廃止
          aggregate ! ProductProtocol.ObsoleteProduct(productId, obsoleteProbe.ref)
          obsoleteProbe.receiveMessage() shouldBe a[ProductProtocol.ObsoleteProductSucceeded]

          // 廃止後の更新試行
          aggregate ! ProductProtocol.UpdateProduct(
            productId,
            ProductName("更新試行"),
            CategoryCode("CAT-002"),
            StorageCondition.Refrigerated,
            updateProbe.ref
          )

          val reply = updateProbe.receiveMessage()
          reply shouldBe a[ProductProtocol.UpdateProductFailed]
        }
      }
    }
  }
}
