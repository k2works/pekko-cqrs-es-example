package io.github.j5ik2o.pcqrses.query.interfaceAdapter.dao

import slick.lifted.{PrimaryKey, ProvenShape}

trait WarehouseZonesComponent extends SlickDaoSupport {
  import profile.api._

  final case class WarehouseZonesRecord(
      id: String,
      warehouseId: String,
      zoneCode: String,
      name: String,
      zoneType: String,
      capacitySqm: BigDecimal,
      isActive: Boolean,
      createdAt: java.sql.Timestamp,
      updatedAt: java.sql.Timestamp
  ) extends Record

  final case class WarehouseZones(tag: Tag) extends TableBase[WarehouseZonesRecord](tag, "warehouse_zones") {
    def id: Rep[String]                   = column[String]("id")
    def warehouseId: Rep[String]          = column[String]("warehouse_id")
    def zoneCode: Rep[String]             = column[String]("zone_code")
    def name: Rep[String]                 = column[String]("name")
    def zoneType: Rep[String]             = column[String]("zone_type")
    def capacitySqm: Rep[BigDecimal]      = column[BigDecimal]("capacity_sqm")
    def isActive: Rep[Boolean]            = column[Boolean]("is_active")
    def createdAt: Rep[java.sql.Timestamp] = column[java.sql.Timestamp]("created_at")
    def updatedAt: Rep[java.sql.Timestamp] = column[java.sql.Timestamp]("updated_at")

    def pk: PrimaryKey = primaryKey("pk", id)

    override def * : ProvenShape[WarehouseZonesRecord] =
      (id, warehouseId, zoneCode, name, zoneType, capacitySqm, isActive, createdAt, updatedAt) <> (
        WarehouseZonesRecord.apply,
        WarehouseZonesRecord.unapply
      )
  }

  object WarehouseZonesDao extends TableQuery(WarehouseZones.apply)
}
