package io.github.j5ik2o.pcqrses.query.interfaceAdapter.dao

import slick.lifted.{PrimaryKey, ProvenShape}

trait InventoriesComponent extends SlickDaoSupport {
  import profile.api._

  final case class InventoriesRecord(
      id: String,
      productId: String,
      warehouseZoneId: String,
      availableQuantity: BigDecimal,
      reservedQuantity: BigDecimal,
      version: Long,
      createdAt: java.sql.Timestamp,
      updatedAt: java.sql.Timestamp
  ) extends Record

  final case class Inventories(tag: Tag) extends TableBase[InventoriesRecord](tag, "inventories") {
    def id: Rep[String]                   = column[String]("id")
    def productId: Rep[String]            = column[String]("product_id")
    def warehouseZoneId: Rep[String]      = column[String]("warehouse_zone_id")
    def availableQuantity: Rep[BigDecimal] = column[BigDecimal]("available_quantity")
    def reservedQuantity: Rep[BigDecimal] = column[BigDecimal]("reserved_quantity")
    def version: Rep[Long]                = column[Long]("version")
    def createdAt: Rep[java.sql.Timestamp] = column[java.sql.Timestamp]("created_at")
    def updatedAt: Rep[java.sql.Timestamp] = column[java.sql.Timestamp]("updated_at")

    def pk: PrimaryKey = primaryKey("pk", id)

    override def * : ProvenShape[InventoriesRecord] =
      (id, productId, warehouseZoneId, availableQuantity, reservedQuantity, version, createdAt, updatedAt) <> (
        InventoriesRecord.apply,
        InventoriesRecord.unapply
      )
  }

  object InventoriesDao extends TableQuery(Inventories.apply)
}
