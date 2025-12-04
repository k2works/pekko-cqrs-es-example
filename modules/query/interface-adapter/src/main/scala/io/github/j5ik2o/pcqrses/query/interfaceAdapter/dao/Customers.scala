package io.github.j5ik2o.pcqrses.query.interfaceAdapter.dao

import slick.lifted.{PrimaryKey, ProvenShape}

trait CustomersComponent extends SlickDaoSupport {
  import profile.api._

  final case class CustomersRecord(
      id: String,
      customerCode: String,
      name: String,
      customerType: String,
      isActive: Boolean,
      createdAt: java.sql.Timestamp,
      updatedAt: java.sql.Timestamp
  ) extends Record

  final case class Customers(tag: Tag) extends TableBase[CustomersRecord](tag, "customers") {
    def id: Rep[String]                   = column[String]("id")
    def customerCode: Rep[String]         = column[String]("customer_code")
    def name: Rep[String]                 = column[String]("name")
    def customerType: Rep[String]         = column[String]("customer_type")
    def isActive: Rep[Boolean]            = column[Boolean]("is_active")
    def createdAt: Rep[java.sql.Timestamp] = column[java.sql.Timestamp]("created_at")
    def updatedAt: Rep[java.sql.Timestamp] = column[java.sql.Timestamp]("updated_at")

    def pk: PrimaryKey = primaryKey("pk", id)

    override def * : ProvenShape[CustomersRecord] =
      (id, customerCode, name, customerType, isActive, createdAt, updatedAt) <> (
        CustomersRecord.apply,
        CustomersRecord.unapply
      )
  }

  object CustomersDao extends TableQuery(Customers.apply)
}
