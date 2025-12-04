package io.github.j5ik2o.pcqrses.command.interfaceAdapter.aggregate.inventory

import com.typesafe.config.{Config, ConfigFactory}
import io.github.j5ik2o.pcqrses.command.domain.inventory.*
import io.github.j5ik2o.pcqrses.command.interfaceAdapter.contract.inventory.InventoryProtocol
import io.github.j5ik2o.pcqrses.command.interfaceAdapter.test.ActorSpec
import org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers

import java.nio.file.Files
import java.util.{Comparator, UUID}

object InventoryAggregateSpec {
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

class InventoryAggregateSpec
  extends ActorSpec(InventoryAggregateSpec.config)
  with Matchers
  with Eventually
  with BeforeAndAfterAll {

  override def afterAll(): Unit = {
    super.afterAll()
    val snapshotDir = new java.io.File(s"target/snapshot/${InventoryAggregateSpec.id}")
    if (snapshotDir.exists()) {
      Files
        .walk(snapshotDir.toPath)
        .sorted(Comparator.reverseOrder())
        .forEach(Files.delete(_))
    }
  }

  "InventoryAggregate" - {
    "在庫が未作成の状態" - {
      "Createコマンドを受信したとき" - {
        "新しい在庫を作成できる" in {
          val inventoryId = InventoryId.generate()
          val productId = ProductId.generate()
          val warehouseZoneId = WarehouseZoneId.generate()
          val probe = TestProbe[InventoryProtocol.CreateInventoryReply]()
          val aggregate = spawn(InventoryAggregate(inventoryId))

          aggregate ! InventoryProtocol.CreateInventory(
            inventoryId,
            productId,
            warehouseZoneId,
            probe.ref
          )

          val reply = probe.receiveMessage()
          reply shouldBe a[InventoryProtocol.CreateInventorySucceeded]
          val succeeded = reply.asInstanceOf[InventoryProtocol.CreateInventorySucceeded]
          succeeded.id shouldBe inventoryId
        }
      }
    }

    "在庫が作成済みの状態" - {
      "Receiveコマンドを受信したとき" - {
        "在庫を入庫できる" in {
          val inventoryId = InventoryId.generate()
          val productId = ProductId.generate()
          val warehouseZoneId = WarehouseZoneId.generate()
          val createProbe = TestProbe[InventoryProtocol.CreateInventoryReply]()
          val receiveProbe = TestProbe[InventoryProtocol.ReceiveInventoryReply]()
          val aggregate = spawn(InventoryAggregate(inventoryId))

          // 在庫作成
          aggregate ! InventoryProtocol.CreateInventory(
            inventoryId,
            productId,
            warehouseZoneId,
            createProbe.ref
          )
          val createReply = createProbe.receiveMessage()
          createReply shouldBe a[InventoryProtocol.CreateInventorySucceeded]

          // 入庫
          aggregate ! InventoryProtocol.ReceiveInventory(
            inventoryId,
            InventoryQuantity.parseFromBigDecimal(BigDecimal(100.0)).getOrElse(InventoryQuantity.Zero),
            InventoryVersion.parseFromLong(0).getOrElse(InventoryVersion.Initial),
            receiveProbe.ref
          )

          val reply = receiveProbe.receiveMessage()
          reply shouldBe a[InventoryProtocol.ReceiveInventorySucceeded]
          val succeeded = reply.asInstanceOf[InventoryProtocol.ReceiveInventorySucceeded]
          succeeded.newVersion shouldBe InventoryVersion.parseFromLong(1).getOrElse(InventoryVersion.Initial)
        }
      }

      "Reserveコマンドを受信したとき（在庫が十分）" - {
        "在庫を引当できる" in {
          val inventoryId = InventoryId.generate()
          val productId = ProductId.generate()
          val warehouseZoneId = WarehouseZoneId.generate()
          val createProbe = TestProbe[InventoryProtocol.CreateInventoryReply]()
          val receiveProbe = TestProbe[InventoryProtocol.ReceiveInventoryReply]()
          val reserveProbe = TestProbe[InventoryProtocol.ReserveInventoryReply]()
          val aggregate = spawn(InventoryAggregate(inventoryId))

          // 在庫作成
          aggregate ! InventoryProtocol.CreateInventory(
            inventoryId,
            productId,
            warehouseZoneId,
            createProbe.ref
          )
          createProbe.receiveMessage()

          // 入庫
          aggregate ! InventoryProtocol.ReceiveInventory(
            inventoryId,
            InventoryQuantity.parseFromBigDecimal(BigDecimal(100.0)).getOrElse(InventoryQuantity.Zero),
            InventoryVersion.parseFromLong(0).getOrElse(InventoryVersion.Initial),
            receiveProbe.ref
          )
          receiveProbe.receiveMessage()

          // 引当
          aggregate ! InventoryProtocol.ReserveInventory(
            inventoryId,
            InventoryQuantity.parseFromBigDecimal(BigDecimal(50.0)).getOrElse(InventoryQuantity.Zero),
            InventoryVersion.parseFromLong(1).getOrElse(InventoryVersion.Initial),
            reserveProbe.ref
          )

          val reply = reserveProbe.receiveMessage()
          reply shouldBe a[InventoryProtocol.ReserveInventorySucceeded]
          val succeeded = reply.asInstanceOf[InventoryProtocol.ReserveInventorySucceeded]
          succeeded.newVersion shouldBe InventoryVersion.parseFromLong(2).getOrElse(InventoryVersion.Initial)
        }
      }

      "Reserveコマンドを受信したとき（在庫不足）" - {
        "エラーを返す" in {
          val inventoryId = InventoryId.generate()
          val productId = ProductId.generate()
          val warehouseZoneId = WarehouseZoneId.generate()
          val createProbe = TestProbe[InventoryProtocol.CreateInventoryReply]()
          val receiveProbe = TestProbe[InventoryProtocol.ReceiveInventoryReply]()
          val reserveProbe = TestProbe[InventoryProtocol.ReserveInventoryReply]()
          val aggregate = spawn(InventoryAggregate(inventoryId))

          // 在庫作成
          aggregate ! InventoryProtocol.CreateInventory(
            inventoryId,
            productId,
            warehouseZoneId,
            createProbe.ref
          )
          createProbe.receiveMessage()

          // 入庫
          aggregate ! InventoryProtocol.ReceiveInventory(
            inventoryId,
            InventoryQuantity.parseFromBigDecimal(BigDecimal(100.0)).getOrElse(InventoryQuantity.Zero),
            InventoryVersion.parseFromLong(0).getOrElse(InventoryVersion.Initial),
            receiveProbe.ref
          )
          receiveProbe.receiveMessage()

          // 在庫不足での引当試行
          aggregate ! InventoryProtocol.ReserveInventory(
            inventoryId,
            InventoryQuantity.parseFromBigDecimal(BigDecimal(150.0)).getOrElse(InventoryQuantity.Zero), // 在庫100に対して150を引当
            InventoryVersion.parseFromLong(1).getOrElse(InventoryVersion.Initial),
            reserveProbe.ref
          )

          val reply = reserveProbe.receiveMessage()
          reply shouldBe a[InventoryProtocol.ReserveInventoryFailed]
        }
      }

      "Releaseコマンドを受信したとき" - {
        "引当を解除できる" in {
          val inventoryId = InventoryId.generate()
          val productId = ProductId.generate()
          val warehouseZoneId = WarehouseZoneId.generate()
          val createProbe = TestProbe[InventoryProtocol.CreateInventoryReply]()
          val receiveProbe = TestProbe[InventoryProtocol.ReceiveInventoryReply]()
          val reserveProbe = TestProbe[InventoryProtocol.ReserveInventoryReply]()
          val releaseProbe = TestProbe[InventoryProtocol.ReleaseInventoryReply]()
          val aggregate = spawn(InventoryAggregate(inventoryId))

          // 在庫作成 -> 入庫 -> 引当
          aggregate ! InventoryProtocol.CreateInventory(
            inventoryId,
            productId,
            warehouseZoneId,
            createProbe.ref
          )
          createProbe.receiveMessage()

          aggregate ! InventoryProtocol.ReceiveInventory(
            inventoryId,
            InventoryQuantity.parseFromBigDecimal(BigDecimal(100.0)).getOrElse(InventoryQuantity.Zero),
            InventoryVersion.parseFromLong(0).getOrElse(InventoryVersion.Initial),
            receiveProbe.ref
          )
          receiveProbe.receiveMessage()

          aggregate ! InventoryProtocol.ReserveInventory(
            inventoryId,
            InventoryQuantity.parseFromBigDecimal(BigDecimal(50.0)).getOrElse(InventoryQuantity.Zero),
            InventoryVersion.parseFromLong(1).getOrElse(InventoryVersion.Initial),
            reserveProbe.ref
          )
          reserveProbe.receiveMessage()

          // 引当解除
          aggregate ! InventoryProtocol.ReleaseInventory(
            inventoryId,
            InventoryQuantity.parseFromBigDecimal(BigDecimal(30.0)).getOrElse(InventoryQuantity.Zero),
            InventoryVersion.parseFromLong(2).getOrElse(InventoryVersion.Initial),
            releaseProbe.ref
          )

          val reply = releaseProbe.receiveMessage()
          reply shouldBe a[InventoryProtocol.ReleaseInventorySucceeded]
          val succeeded = reply.asInstanceOf[InventoryProtocol.ReleaseInventorySucceeded]
          succeeded.newVersion shouldBe InventoryVersion.parseFromLong(3).getOrElse(InventoryVersion.Initial)
        }
      }

      "Issueコマンドを受信したとき" - {
        "在庫を出庫できる" in {
          val inventoryId = InventoryId.generate()
          val productId = ProductId.generate()
          val warehouseZoneId = WarehouseZoneId.generate()
          val createProbe = TestProbe[InventoryProtocol.CreateInventoryReply]()
          val receiveProbe = TestProbe[InventoryProtocol.ReceiveInventoryReply]()
          val reserveProbe = TestProbe[InventoryProtocol.ReserveInventoryReply]()
          val issueProbe = TestProbe[InventoryProtocol.IssueInventoryReply]()
          val aggregate = spawn(InventoryAggregate(inventoryId))

          // 在庫作成 -> 入庫 -> 引当
          aggregate ! InventoryProtocol.CreateInventory(
            inventoryId,
            productId,
            warehouseZoneId,
            createProbe.ref
          )
          createProbe.receiveMessage()

          aggregate ! InventoryProtocol.ReceiveInventory(
            inventoryId,
            InventoryQuantity.parseFromBigDecimal(BigDecimal(100.0)).getOrElse(InventoryQuantity.Zero),
            InventoryVersion.parseFromLong(0).getOrElse(InventoryVersion.Initial),
            receiveProbe.ref
          )
          receiveProbe.receiveMessage()

          aggregate ! InventoryProtocol.ReserveInventory(
            inventoryId,
            InventoryQuantity.parseFromBigDecimal(BigDecimal(50.0)).getOrElse(InventoryQuantity.Zero),
            InventoryVersion.parseFromLong(1).getOrElse(InventoryVersion.Initial),
            reserveProbe.ref
          )
          reserveProbe.receiveMessage()

          // 出庫
          aggregate ! InventoryProtocol.IssueInventory(
            inventoryId,
            InventoryQuantity.parseFromBigDecimal(BigDecimal(50.0)).getOrElse(InventoryQuantity.Zero),
            InventoryVersion.parseFromLong(2).getOrElse(InventoryVersion.Initial),
            issueProbe.ref
          )

          val reply = issueProbe.receiveMessage()
          reply shouldBe a[InventoryProtocol.IssueInventorySucceeded]
          val succeeded = reply.asInstanceOf[InventoryProtocol.IssueInventorySucceeded]
          succeeded.newVersion shouldBe InventoryVersion.parseFromLong(3).getOrElse(InventoryVersion.Initial)
        }
      }

      "Adjustコマンドを受信したとき" - {
        "在庫調整ができる" in {
          val inventoryId = InventoryId.generate()
          val productId = ProductId.generate()
          val warehouseZoneId = WarehouseZoneId.generate()
          val createProbe = TestProbe[InventoryProtocol.CreateInventoryReply]()
          val receiveProbe = TestProbe[InventoryProtocol.ReceiveInventoryReply]()
          val adjustProbe = TestProbe[InventoryProtocol.AdjustInventoryReply]()
          val aggregate = spawn(InventoryAggregate(inventoryId))

          // 在庫作成 -> 入庫
          aggregate ! InventoryProtocol.CreateInventory(
            inventoryId,
            productId,
            warehouseZoneId,
            createProbe.ref
          )
          createProbe.receiveMessage()

          aggregate ! InventoryProtocol.ReceiveInventory(
            inventoryId,
            InventoryQuantity.parseFromBigDecimal(BigDecimal(100.0)).getOrElse(InventoryQuantity.Zero),
            InventoryVersion.parseFromLong(0).getOrElse(InventoryVersion.Initial),
            receiveProbe.ref
          )
          receiveProbe.receiveMessage()

          // 在庫調整
          aggregate ! InventoryProtocol.AdjustInventory(
            inventoryId,
            InventoryQuantity.parseFromBigDecimal(BigDecimal(95.0)).getOrElse(InventoryQuantity.Zero),
            "棚卸調整",
            InventoryVersion.parseFromLong(1).getOrElse(InventoryVersion.Initial),
            adjustProbe.ref
          )

          val reply = adjustProbe.receiveMessage()
          reply shouldBe a[InventoryProtocol.AdjustInventorySucceeded]
          val succeeded = reply.asInstanceOf[InventoryProtocol.AdjustInventorySucceeded]
          succeeded.newVersion shouldBe InventoryVersion.parseFromLong(2).getOrElse(InventoryVersion.Initial)
        }
      }
    }

    "バージョン競合のとき" - {
      "古いバージョンでの操作は失敗する" in {
        val inventoryId = InventoryId.generate()
        val productId = ProductId.generate()
        val warehouseZoneId = WarehouseZoneId.generate()
        val createProbe = TestProbe[InventoryProtocol.CreateInventoryReply]()
        val receiveProbe1 = TestProbe[InventoryProtocol.ReceiveInventoryReply]()
        val receiveProbe2 = TestProbe[InventoryProtocol.ReceiveInventoryReply]()
        val aggregate = spawn(InventoryAggregate(inventoryId))

        // 在庫作成
        aggregate ! InventoryProtocol.CreateInventory(
          inventoryId,
          productId,
          warehouseZoneId,
          createProbe.ref
        )
        createProbe.receiveMessage()

        // 最初の入庫
        aggregate ! InventoryProtocol.ReceiveInventory(
          inventoryId,
          InventoryQuantity.parseFromBigDecimal(BigDecimal(100.0)).getOrElse(InventoryQuantity.Zero),
          InventoryVersion.parseFromLong(0).getOrElse(InventoryVersion.Initial),
          receiveProbe1.ref
        )
        receiveProbe1.receiveMessage()

        // 古いバージョンでの入庫試行
        aggregate ! InventoryProtocol.ReceiveInventory(
          inventoryId,
          InventoryQuantity.parseFromBigDecimal(BigDecimal(50.0)).getOrElse(InventoryQuantity.Zero),
          InventoryVersion.parseFromLong(0).getOrElse(InventoryVersion.Initial), // 古いバージョン
          receiveProbe2.ref
        )

        val reply = receiveProbe2.receiveMessage()
        reply shouldBe a[InventoryProtocol.ReceiveInventoryFailed]
      }
    }
  }
}
