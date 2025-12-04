package io.github.j5ik2o.pcqrses.query.interfaceAdapter.dao

import slick.lifted.{PrimaryKey, ProvenShape}

trait WarehousesComponent extends SlickDaoSupport {
  import profile.api._

  final case class WarehousesRecord(
      id: String,
      warehouseCode: String,
      name: String,
      location: String,
      isActive: Boolean,
      createdAt: java.sql.Timestamp,
      updatedAt: java.sql.Timestamp
  ) extends Record

  final case class Warehouses(tag: Tag) extends TableBase[WarehousesRecord](tag, "warehouses") {
    def id: Rep[String]                   = column[String]("id")
    def warehouseCode: Rep[String]        = column[String]("warehouse_code")
    def name: Rep[String]                 = column[String]("name")
    def location: Rep[String]             = column[String]("location")
    def isActive: Rep[Boolean]            = column[Boolean]("is_active")
    def createdAt: Rep[java.sql.Timestamp] = column[java.sql.Timestamp]("created_at")
    def updatedAt: Rep[java.sql.Timestamp] = column[java.sql.Timestamp]("updated_at")

    def pk: PrimaryKey = primaryKey("pk", id)

    override def * : ProvenShape[WarehousesRecord] =
      (id, warehouseCode, name, location, isActive, createdAt, updatedAt) <> (
        WarehousesRecord.apply,
        WarehousesRecord.unapply
      )
  }

  object WarehousesDao extends TableQuery(Warehouses.apply)
}
