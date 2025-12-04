package io.github.j5ik2o.pcqrses.query.interfaceAdapter.dao

import slick.lifted.{PrimaryKey, ProvenShape}

trait ProductsComponent extends SlickDaoSupport {
  import profile.api._

  final case class ProductsRecord(
      id: String,
      productCode: String,
      name: String,
      categoryCode: String,
      storageCondition: String,
      isObsolete: Boolean,
      createdAt: java.sql.Timestamp,
      updatedAt: java.sql.Timestamp
  ) extends Record

  final case class Products(tag: Tag) extends TableBase[ProductsRecord](tag, "products") {
    def id: Rep[String]                   = column[String]("id")
    def productCode: Rep[String]          = column[String]("product_code")
    def name: Rep[String]                 = column[String]("name")
    def categoryCode: Rep[String]         = column[String]("category_code")
    def storageCondition: Rep[String]     = column[String]("storage_condition")
    def isObsolete: Rep[Boolean]          = column[Boolean]("is_obsolete")
    def createdAt: Rep[java.sql.Timestamp] = column[java.sql.Timestamp]("created_at")
    def updatedAt: Rep[java.sql.Timestamp] = column[java.sql.Timestamp]("updated_at")

    def pk: PrimaryKey = primaryKey("pk", id)

    override def * : ProvenShape[ProductsRecord] =
      (id, productCode, name, categoryCode, storageCondition, isObsolete, createdAt, updatedAt) <> (
        ProductsRecord.apply,
        ProductsRecord.unapply
      )
  }

  object ProductsDao extends TableQuery(Products.apply)
}
