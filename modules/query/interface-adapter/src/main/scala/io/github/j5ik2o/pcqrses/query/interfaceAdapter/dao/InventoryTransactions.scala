package io.github.j5ik2o.pcqrses.query.interfaceAdapter.dao

import slick.lifted.{PrimaryKey, ProvenShape}

trait InventoryTransactionsComponent extends SlickDaoSupport {
  import profile.api._

  final case class InventoryTransactionsRecord(
      id: String,
      inventoryId: String,
      transactionType: String,
      quantity: BigDecimal,
      fromWarehouseZoneId: Option[String],
      toWarehouseZoneId: Option[String],
      reason: Option[String],
      occurredAt: java.sql.Timestamp,
      createdAt: java.sql.Timestamp
  ) extends Record

  final case class InventoryTransactions(tag: Tag)
      extends TableBase[InventoryTransactionsRecord](tag, "inventory_transactions") {
    def id: Rep[String]                        = column[String]("id")
    def inventoryId: Rep[String]               = column[String]("inventory_id")
    def transactionType: Rep[String]           = column[String]("transaction_type")
    def quantity: Rep[BigDecimal]              = column[BigDecimal]("quantity")
    def fromWarehouseZoneId: Rep[Option[String]] = column[Option[String]]("from_warehouse_zone_id")
    def toWarehouseZoneId: Rep[Option[String]] = column[Option[String]]("to_warehouse_zone_id")
    def reason: Rep[Option[String]]            = column[Option[String]]("reason")
    def occurredAt: Rep[java.sql.Timestamp]    = column[java.sql.Timestamp]("occurred_at")
    def createdAt: Rep[java.sql.Timestamp]     = column[java.sql.Timestamp]("created_at")

    def pk: PrimaryKey = primaryKey("pk", id)

    override def * : ProvenShape[InventoryTransactionsRecord] =
      (id, inventoryId, transactionType, quantity, fromWarehouseZoneId, toWarehouseZoneId, reason, occurredAt, createdAt) <> (
        InventoryTransactionsRecord.apply,
        InventoryTransactionsRecord.unapply
      )
  }

  object InventoryTransactionsDao extends TableQuery(InventoryTransactions.apply)
}
