package io.github.j5ik2o.pcqrses.readModelUpdater

import com.amazonaws.services.lambda.runtime.events.DynamodbEvent
import com.amazonaws.services.lambda.runtime.events.DynamodbEvent.DynamodbStreamRecord
import com.amazonaws.services.lambda.runtime.{Context, RequestHandler}
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.typesafe.config.ConfigFactory
import io.github.j5ik2o.pcqrses.command.domain.users.UserAccountEvent
import io.github.j5ik2o.pcqrses.command.domain.inventory.{
  ProductEvent,
  CustomerEvent,
  WarehouseEvent,
  WarehouseZoneEvent,
  InventoryEvent
}
import io.github.j5ik2o.pcqrses.query.interfaceAdapter.dao.{
  UserAccountsComponent,
  ProductsComponent,
  CustomersComponent,
  WarehousesComponent,
  WarehouseZonesComponent,
  InventoriesComponent,
  InventoryTransactionsComponent
}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.persistence.PersistentRepr
import org.apache.pekko.serialization.SerializationExtension
import org.slf4j.LoggerFactory
import slick.basic.DatabaseConfig
import slick.jdbc.JdbcProfile

import java.nio.ByteBuffer
import java.sql.Timestamp
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.jdk.CollectionConverters.*
import scala.util.Try

class LambdaHandler extends RequestHandler[DynamodbEvent, LambdaResponse] {

  private val logger = LoggerFactory.getLogger(getClass)

  private val objectMapper = new ObjectMapper()
  objectMapper.registerModule(DefaultScalaModule)

  private val config = ConfigFactory.load()
  private lazy val system = ActorSystem("read-model-updater", config)
  private lazy val serialization = SerializationExtension(system)

  private val databaseOperationTimeout: Duration =
    Duration.fromNanos(config.getDuration("read-model-updater.timeouts.database-operation").toNanos)

  private val databaseConfig: DatabaseConfig[JdbcProfile] =
    DatabaseConfig.forConfig[JdbcProfile]("read-model-updater.slick", config)

  private val UserAccountEntityTypePrefix = "UserAccount-"
  private val ProductEntityTypePrefix = "Product-"
  private val CustomerEntityTypePrefix = "Customer-"
  private val WarehouseEntityTypePrefix = "Warehouse-"
  private val WarehouseZoneEntityTypePrefix = "WarehouseZone-"
  private val InventoryEntityTypePrefix = "Inventory-"

  override def handleRequest(input: DynamodbEvent, context: Context): LambdaResponse = {
    try {
      logger.info(s"Received DynamoDB event with ${input.getRecords.size} records")

      val results = input.getRecords.asScala.map(processRecord).toList

      val failures = results.collect { case Left(error) => error }
      val successes = results.collect { case Right(_) => () }

      if (failures.nonEmpty) {
        logger.error(s"Failed to process ${failures.size} out of ${results.size} records")
        failures.foreach { error =>
          logger.error(s"Processing error: ${error.message}", error.exception.orNull)
        }
        LambdaResponse(
          statusCode = 207, // Multi-Status
          body = objectMapper.writeValueAsString(
            ResponseBody(
              message = s"Processed ${successes.size} records successfully, ${failures.size} failed",
              error = Some(failures.map(_.message).mkString("; "))
            )
          )
        )
      } else {
        logger.info(s"Successfully processed ${successes.size} records")
        LambdaResponse(
          statusCode = 200,
          body = objectMapper.writeValueAsString(
            ResponseBody(message = s"Successfully processed ${successes.size} records")
          )
        )
      }
    } catch {
      case ex: Exception =>
        logger.error("Unexpected error processing DynamoDB event", ex)
        LambdaResponse(
          statusCode = 500,
          body = objectMapper.writeValueAsString(
            ResponseBody(message = "Internal server error", error = Some(ex.getMessage))
          )
        )
    }
  }

  private def processRecord(record: DynamodbStreamRecord): Either[ProcessingError, Unit] = {
    try {
      val tableName = record.getEventSourceARN.split("/")(1)
      if (tableName != "Journal") {
        logger.debug(s"Skipping record from table: $tableName")
        return Right(())
      }

      val newImage = Option(record.getDynamodb.getNewImage)
      if (newImage.isEmpty) {
        logger.debug("Skipping record without NewImage (likely DELETE event)")
        return Right(())
      }

      val attributes = newImage.get.asScala

      val persistenceIdOpt = Option(attributes.get("persistence-id"))
        .flatMap(attrOpt => Option(attrOpt.map(_.getS).orNull))

      val persistenceId = persistenceIdOpt.getOrElse("")
      val isRelevant = persistenceId.startsWith(UserAccountEntityTypePrefix) ||
        persistenceId.startsWith(ProductEntityTypePrefix) ||
        persistenceId.startsWith(CustomerEntityTypePrefix) ||
        persistenceId.startsWith(WarehouseEntityTypePrefix) ||
        persistenceId.startsWith(WarehouseZoneEntityTypePrefix) ||
        persistenceId.startsWith(InventoryEntityTypePrefix)

      if (!isRelevant) {
        logger.debug(s"Skipping record with persistence-id: $persistenceId")
        return Right(())
      }

      val messageAttrOpt = attributes.get("message")
      if (messageAttrOpt.isEmpty) {
        logger.warn("Record missing message attribute")
        return Left(ProcessingError("Missing message attribute", None))
      }
      val messageAttr = messageAttrOpt.get
      logger.info(s"Message attribute type: B=${messageAttr.getB != null}, BS=${messageAttr.getBS != null}, S=${messageAttr.getS != null}")

      val serializerIdAttrOpt = attributes.get("serializer-id")
      val manifestAttrOpt = attributes.get("manifest")

      val messageBytes = Option(messageAttr.getB) match {
        case Some(binaryData) =>
          convertToBytes(binaryData)
        case None =>
          throw new IllegalArgumentException("Message attribute B (binary) is required")
      }

      // PersistentRepr からイベントを取り出して処理
      deserializePersistentReprAndProcess(messageBytes)
    } catch {
      case ex: Exception =>
        logger.error("Error processing record", ex)
        Left(ProcessingError(s"Error processing record: ${ex.getMessage}", Some(ex)))
    }
  }

  private def convertToBytes(binaryData: ByteBuffer): Array[Byte] = {
    val bytes = if (binaryData.hasArray) {
      binaryData.array()
    } else {
      val arr = new Array[Byte](binaryData.remaining())
      binaryData.get(arr)
      arr
    }
    logger.debug(s"Converted ByteBuffer to ${bytes.length} bytes")
    bytes
  }

  private def deserializePersistentRepr(bytes: Array[Byte]): Try[PersistentRepr] = Try {
    val persistentReprSerializer = serialization.serializerFor(classOf[PersistentRepr])
    logger.info(s"Using PersistentRepr serializer: ${persistentReprSerializer.getClass.getName} (ID: ${persistentReprSerializer.identifier})")
    persistentReprSerializer.fromBinary(bytes, classOf[PersistentRepr]).asInstanceOf[PersistentRepr]
  }

  private def deserializePersistentReprAndProcess(bytes: Array[Byte]): Either[ProcessingError, Unit] = {
    try {
      logger.debug(s"Binary data size: ${bytes.length} bytes")

      // PersistentRepr をデシリアライズ
      deserializePersistentRepr(bytes) match {
        case scala.util.Success(persistentRepr) =>
          logger.debug(s"Successfully deserialized PersistentRepr:")
          logger.debug(s"  Persistence ID: ${persistentRepr.persistenceId}")
          logger.debug(s"  Sequence Nr: ${persistentRepr.sequenceNr}")
          logger.debug(s"  Manifest: ${persistentRepr.manifest}")

          // ペイロード（実際のイベント）を取り出す
          persistentRepr.payload match {
            case event: UserAccountEvent =>
              logger.info(s"Processing UserAccountEvent: ${event.getClass.getSimpleName} for entity: ${event.entityId.asString}")
              processUserAccountEvent(event)
            case event: ProductEvent =>
              logger.info(s"Processing ProductEvent: ${event.getClass.getSimpleName} for entity: ${event.entityId.asString}")
              processProductEvent(event)
            case event: CustomerEvent =>
              logger.info(s"Processing CustomerEvent: ${event.getClass.getSimpleName} for entity: ${event.entityId.asString}")
              processCustomerEvent(event)
            case event: WarehouseEvent =>
              logger.info(s"Processing WarehouseEvent: ${event.getClass.getSimpleName} for entity: ${event.entityId.asString}")
              processWarehouseEvent(event)
            case event: WarehouseZoneEvent =>
              logger.info(s"Processing WarehouseZoneEvent: ${event.getClass.getSimpleName} for entity: ${event.entityId.asString}")
              processWarehouseZoneEvent(event)
            case event: InventoryEvent =>
              logger.info(s"Processing InventoryEvent: ${event.getClass.getSimpleName} for entity: ${event.entityId.asString}")
              processInventoryEvent(event)
            case other =>
              logger.warn(s"Unknown event type: ${other.getClass.getName}")
              Right(())
          }

        case scala.util.Failure(ex) =>
          logger.error(s"Failed to deserialize PersistentRepr: ${ex.getMessage}", ex)
          Left(ProcessingError(s"Error deserializing PersistentRepr: ${ex.getMessage}", Some(ex)))
      }
    } catch {
      case ex: Exception =>
        logger.error("Error processing PersistentRepr", ex)
        Left(ProcessingError(s"Error processing PersistentRepr: ${ex.getMessage}", Some(ex)))
    }
  }

  private def processUserAccountEvent(event: UserAccountEvent): Either[ProcessingError, Unit] = {
    try {
      val db = databaseConfig.db
      val component = new UserAccountsComponent {
        override val profile: JdbcProfile = databaseConfig.profile
      }
      import databaseConfig.profile.api.*

      val action = event match {
        case UserAccountEvent.Created_V1(_, entityId, name, _, occurredAt) =>
          val record = component.UserAccountsRecord(
            id = entityId.asString,
            firstName = name.breachEncapsulationOfFirstName.asString,
            lastName = name.breachEncapsulationOfLastName.asString,
            createdAt = Timestamp.from(occurredAt.asInstant()),
            updatedAt = Timestamp.from(occurredAt.asInstant())
          )
          component.UserAccountsDao.insertOrUpdate(record)

        case UserAccountEvent.Renamed_V1(_, entityId, _, newName, occurredAt) =>
          component.UserAccountsDao
            .filter(_.id === entityId.asString)
            .map(r => (r.firstName, r.lastName, r.updatedAt))
            .update(
              (newName.breachEncapsulationOfFirstName.asString,
                newName.breachEncapsulationOfLastName.asString,
                Timestamp.from(occurredAt.asInstant()))
            )

        case UserAccountEvent.Deleted_V1(_, entityId, _) =>
          component.UserAccountsDao.filter(_.id === entityId.asString).delete
      }

      val result = Await.result(db.run(action), databaseOperationTimeout)
      logger.debug(s"Successfully processed event: ${event.getClass.getSimpleName} for entity: ${event.entityId.asString}")
      Right(())
    } catch {
      case ex: Exception =>
        logger.error(s"Error processing UserAccountEvent: ${event.getClass.getSimpleName}", ex)
        Left(ProcessingError(s"Error processing event: ${ex.getMessage}", Some(ex)))
    }
  }

  private def processProductEvent(event: ProductEvent): Either[ProcessingError, Unit] = {
    try {
      val db = databaseConfig.db
      val component = new ProductsComponent {
        override val profile: JdbcProfile = databaseConfig.profile
      }
      import databaseConfig.profile.api.*

      val action = event match {
        case ProductEvent.Created_V1(_, entityId, productCode, name, categoryCode, storageCondition, occurredAt) =>
          val record = component.ProductsRecord(
            id = entityId.asString,
            productCode = productCode.value,
            name = name.value,
            categoryCode = categoryCode.value,
            storageCondition = storageCondition.toString, // RT, RF, FZ
            isObsolete = false,
            createdAt = Timestamp.from(occurredAt.asInstant()),
            updatedAt = Timestamp.from(occurredAt.asInstant())
          )
          component.ProductsDao.insertOrUpdate(record)

        case ProductEvent.Updated_V1(_, entityId, _, newName, newCategoryCode, newStorageCondition, occurredAt) =>
          component.ProductsDao
            .filter(_.id === entityId.asString)
            .map(r => (r.name, r.categoryCode, r.storageCondition, r.updatedAt))
            .update(
              (newName.value,
                newCategoryCode.value,
                newStorageCondition.toString,
                Timestamp.from(occurredAt.asInstant()))
            )

        case ProductEvent.Obsoleted_V1(_, entityId, occurredAt) =>
          component.ProductsDao
            .filter(_.id === entityId.asString)
            .map(r => (r.isObsolete, r.updatedAt))
            .update((true, Timestamp.from(occurredAt.asInstant())))
      }

      val result = Await.result(db.run(action), databaseOperationTimeout)
      logger.debug(s"Successfully processed ProductEvent: ${event.getClass.getSimpleName} for entity: ${event.entityId.asString}")
      Right(())
    } catch {
      case ex: Exception =>
        logger.error(s"Error processing ProductEvent: ${event.getClass.getSimpleName}", ex)
        Left(ProcessingError(s"Error processing event: ${ex.getMessage}", Some(ex)))
    }
  }

  private def processCustomerEvent(event: CustomerEvent): Either[ProcessingError, Unit] = {
    try {
      val db = databaseConfig.db
      val component = new CustomersComponent {
        override val profile: JdbcProfile = databaseConfig.profile
      }
      import databaseConfig.profile.api.*

      val action = event match {
        case CustomerEvent.Created_V1(_, entityId, customerCode, name, customerType, occurredAt) =>
          val record = component.CustomersRecord(
            id = entityId.asString,
            customerCode = customerCode.value,
            name = name.value,
            customerType = customerType.toString, // LARGE, MEDIUM, SMALL
            isActive = true,
            createdAt = Timestamp.from(occurredAt.asInstant()),
            updatedAt = Timestamp.from(occurredAt.asInstant())
          )
          component.CustomersDao.insertOrUpdate(record)

        case CustomerEvent.Updated_V1(_, entityId, _, newName, newCustomerType, occurredAt) =>
          component.CustomersDao
            .filter(_.id === entityId.asString)
            .map(r => (r.name, r.customerType, r.updatedAt))
            .update((newName.value, newCustomerType.toString, Timestamp.from(occurredAt.asInstant())))

        case CustomerEvent.Deactivated_V1(_, entityId, occurredAt) =>
          component.CustomersDao
            .filter(_.id === entityId.asString)
            .map(r => (r.isActive, r.updatedAt))
            .update((false, Timestamp.from(occurredAt.asInstant())))

        case CustomerEvent.Reactivated_V1(_, entityId, occurredAt) =>
          component.CustomersDao
            .filter(_.id === entityId.asString)
            .map(r => (r.isActive, r.updatedAt))
            .update((true, Timestamp.from(occurredAt.asInstant())))
      }

      val result = Await.result(db.run(action), databaseOperationTimeout)
      logger.debug(s"Successfully processed CustomerEvent: ${event.getClass.getSimpleName} for entity: ${event.entityId.asString}")
      Right(())
    } catch {
      case ex: Exception =>
        logger.error(s"Error processing CustomerEvent: ${event.getClass.getSimpleName}", ex)
        Left(ProcessingError(s"Error processing event: ${ex.getMessage}", Some(ex)))
    }
  }

  private def processWarehouseEvent(event: WarehouseEvent): Either[ProcessingError, Unit] = {
    try {
      val db = databaseConfig.db
      val component = new WarehousesComponent {
        override val profile: JdbcProfile = databaseConfig.profile
      }
      import databaseConfig.profile.api.*

      val action = event match {
        case WarehouseEvent.Created_V1(_, entityId, warehouseCode, name, location, occurredAt) =>
          val record = component.WarehousesRecord(
            id = entityId.asString,
            warehouseCode = warehouseCode.value,
            name = name.value,
            location = location.value,
            isActive = true,
            createdAt = Timestamp.from(occurredAt.asInstant()),
            updatedAt = Timestamp.from(occurredAt.asInstant())
          )
          component.WarehousesDao.insertOrUpdate(record)

        case WarehouseEvent.Updated_V1(_, entityId, _, newName, newLocation, occurredAt) =>
          component.WarehousesDao
            .filter(_.id === entityId.asString)
            .map(r => (r.name, r.location, r.updatedAt))
            .update((newName.value, newLocation.value, Timestamp.from(occurredAt.asInstant())))

        case WarehouseEvent.Deactivated_V1(_, entityId, occurredAt) =>
          component.WarehousesDao
            .filter(_.id === entityId.asString)
            .map(r => (r.isActive, r.updatedAt))
            .update((false, Timestamp.from(occurredAt.asInstant())))

        case WarehouseEvent.Reactivated_V1(_, entityId, occurredAt) =>
          component.WarehousesDao
            .filter(_.id === entityId.asString)
            .map(r => (r.isActive, r.updatedAt))
            .update((true, Timestamp.from(occurredAt.asInstant())))
      }

      val result = Await.result(db.run(action), databaseOperationTimeout)
      logger.debug(s"Successfully processed WarehouseEvent: ${event.getClass.getSimpleName} for entity: ${event.entityId.asString}")
      Right(())
    } catch {
      case ex: Exception =>
        logger.error(s"Error processing WarehouseEvent: ${event.getClass.getSimpleName}", ex)
        Left(ProcessingError(s"Error processing event: ${ex.getMessage}", Some(ex)))
    }
  }

  private def processWarehouseZoneEvent(event: WarehouseZoneEvent): Either[ProcessingError, Unit] = {
    try {
      val db = databaseConfig.db
      val component = new WarehouseZonesComponent {
        override val profile: JdbcProfile = databaseConfig.profile
      }
      import databaseConfig.profile.api.*

      val action = event match {
        case WarehouseZoneEvent.Created_V1(_, entityId, warehouseId, zoneCode, name, zoneType, capacity, occurredAt) =>
          val record = component.WarehouseZonesRecord(
            id = entityId.asString,
            warehouseId = warehouseId.asString,
            zoneCode = zoneCode.value,
            name = name.value,
            zoneType = zoneType.toString, // RT, RF, FZ
            capacitySqm = capacity.asBigDecimal,
            isActive = true,
            createdAt = Timestamp.from(occurredAt.asInstant()),
            updatedAt = Timestamp.from(occurredAt.asInstant())
          )
          component.WarehouseZonesDao.insertOrUpdate(record)

        case WarehouseZoneEvent.Updated_V1(_, entityId, _, newName, newCapacity, occurredAt) =>
          component.WarehouseZonesDao
            .filter(_.id === entityId.asString)
            .map(r => (r.name, r.capacitySqm, r.updatedAt))
            .update((newName.value, newCapacity.asBigDecimal, Timestamp.from(occurredAt.asInstant())))

        case WarehouseZoneEvent.Deactivated_V1(_, entityId, occurredAt) =>
          component.WarehouseZonesDao
            .filter(_.id === entityId.asString)
            .map(r => (r.isActive, r.updatedAt))
            .update((false, Timestamp.from(occurredAt.asInstant())))

        case WarehouseZoneEvent.Reactivated_V1(_, entityId, occurredAt) =>
          component.WarehouseZonesDao
            .filter(_.id === entityId.asString)
            .map(r => (r.isActive, r.updatedAt))
            .update((true, Timestamp.from(occurredAt.asInstant())))
      }

      val result = Await.result(db.run(action), databaseOperationTimeout)
      logger.debug(s"Successfully processed WarehouseZoneEvent: ${event.getClass.getSimpleName} for entity: ${event.entityId.asString}")
      Right(())
    } catch {
      case ex: Exception =>
        logger.error(s"Error processing WarehouseZoneEvent: ${event.getClass.getSimpleName}", ex)
        Left(ProcessingError(s"Error processing event: ${ex.getMessage}", Some(ex)))
    }
  }

  private def processInventoryEvent(event: InventoryEvent): Either[ProcessingError, Unit] = {
    try {
      val db = databaseConfig.db
      val inventoryComponent = new InventoriesComponent {
        override val profile: JdbcProfile = databaseConfig.profile
      }
      val transactionComponent = new InventoryTransactionsComponent {
        override val profile: JdbcProfile = databaseConfig.profile
      }
      import databaseConfig.profile.api.*
      import wvlet.airframe.ulid.ULID

      val actions = event match {
        case InventoryEvent.Created_V1(eventId, entityId, productId, warehouseZoneId, occurredAt) =>
          val inventoryRecord = inventoryComponent.InventoriesRecord(
            id = entityId.asString,
            productId = productId.asString,
            warehouseZoneId = warehouseZoneId.asString,
            availableQuantity = BigDecimal(0),
            reservedQuantity = BigDecimal(0),
            version = 1,
            createdAt = Timestamp.from(occurredAt.asInstant()),
            updatedAt = Timestamp.from(occurredAt.asInstant())
          )
          DBIO.seq(inventoryComponent.InventoriesDao.insertOrUpdate(inventoryRecord))

        case InventoryEvent.Received_V1(eventId, entityId, _, quantity, newVersion, occurredAt) =>
          val updateAction = inventoryComponent.InventoriesDao
            .filter(_.id === entityId.asString)
            .map(r => (r.availableQuantity, r.version, r.updatedAt))
            .result
            .headOption
            .flatMap {
              case Some((currentQty, _, _)) =>
                inventoryComponent.InventoriesDao
                  .filter(_.id === entityId.asString)
                  .map(r => (r.availableQuantity, r.version, r.updatedAt))
                  .update((currentQty + quantity.amount, newVersion.value, Timestamp.from(occurredAt.asInstant())))
              case None =>
                DBIO.successful(0)
            }
          val txRecord = transactionComponent.InventoryTransactionsRecord(
            id = ULID.newULID.toString,
            inventoryId = entityId.asString,
            transactionType = "RECEIVED",
            quantity = quantity.amount,
            fromWarehouseZoneId = None,
            toWarehouseZoneId = None,
            reason = None,
            occurredAt = Timestamp.from(occurredAt.asInstant()),
            createdAt = Timestamp.from(java.time.Instant.now())
          )
          DBIO.seq(updateAction, transactionComponent.InventoryTransactionsDao.+=(txRecord))

        case InventoryEvent.Reserved_V1(eventId, entityId, _, quantity, newVersion, occurredAt) =>
          val updateAction = inventoryComponent.InventoriesDao
            .filter(_.id === entityId.asString)
            .map(r => (r.availableQuantity, r.reservedQuantity, r.version, r.updatedAt))
            .result
            .headOption
            .flatMap {
              case Some((currentAvailable, currentReserved, _, _)) =>
                inventoryComponent.InventoriesDao
                  .filter(_.id === entityId.asString)
                  .map(r => (r.availableQuantity, r.reservedQuantity, r.version, r.updatedAt))
                  .update((currentAvailable - quantity.amount, currentReserved + quantity.amount, newVersion.value, Timestamp.from(occurredAt.asInstant())))
              case None =>
                DBIO.successful(0)
            }
          val txRecord = transactionComponent.InventoryTransactionsRecord(
            id = ULID.newULID.toString,
            inventoryId = entityId.asString,
            transactionType = "RESERVED",
            quantity = quantity.amount,
            fromWarehouseZoneId = None,
            toWarehouseZoneId = None,
            reason = None,
            occurredAt = Timestamp.from(occurredAt.asInstant()),
            createdAt = Timestamp.from(java.time.Instant.now())
          )
          DBIO.seq(updateAction, transactionComponent.InventoryTransactionsDao.+=(txRecord))

        case InventoryEvent.Released_V1(eventId, entityId, _, quantity, newVersion, occurredAt) =>
          val updateAction = inventoryComponent.InventoriesDao
            .filter(_.id === entityId.asString)
            .map(r => (r.availableQuantity, r.reservedQuantity, r.version, r.updatedAt))
            .result
            .headOption
            .flatMap {
              case Some((currentAvailable, currentReserved, _, _)) =>
                inventoryComponent.InventoriesDao
                  .filter(_.id === entityId.asString)
                  .map(r => (r.availableQuantity, r.reservedQuantity, r.version, r.updatedAt))
                  .update((currentAvailable + quantity.amount, currentReserved - quantity.amount, newVersion.value, Timestamp.from(occurredAt.asInstant())))
              case None =>
                DBIO.successful(0)
            }
          val txRecord = transactionComponent.InventoryTransactionsRecord(
            id = ULID.newULID.toString,
            inventoryId = entityId.asString,
            transactionType = "RELEASED",
            quantity = quantity.amount,
            fromWarehouseZoneId = None,
            toWarehouseZoneId = None,
            reason = None,
            occurredAt = Timestamp.from(occurredAt.asInstant()),
            createdAt = Timestamp.from(java.time.Instant.now())
          )
          DBIO.seq(updateAction, transactionComponent.InventoryTransactionsDao.+=(txRecord))

        case InventoryEvent.Issued_V1(eventId, entityId, _, quantity, newVersion, occurredAt) =>
          val updateAction = inventoryComponent.InventoriesDao
            .filter(_.id === entityId.asString)
            .map(r => (r.reservedQuantity, r.version, r.updatedAt))
            .result
            .headOption
            .flatMap {
              case Some((currentReserved, _, _)) =>
                inventoryComponent.InventoriesDao
                  .filter(_.id === entityId.asString)
                  .map(r => (r.reservedQuantity, r.version, r.updatedAt))
                  .update((currentReserved - quantity.amount, newVersion.value, Timestamp.from(occurredAt.asInstant())))
              case None =>
                DBIO.successful(0)
            }
          val txRecord = transactionComponent.InventoryTransactionsRecord(
            id = ULID.newULID.toString,
            inventoryId = entityId.asString,
            transactionType = "ISSUED",
            quantity = quantity.amount,
            fromWarehouseZoneId = None,
            toWarehouseZoneId = None,
            reason = None,
            occurredAt = Timestamp.from(occurredAt.asInstant()),
            createdAt = Timestamp.from(java.time.Instant.now())
          )
          DBIO.seq(updateAction, transactionComponent.InventoryTransactionsDao.+=(txRecord))

        case InventoryEvent.Adjusted_V1(eventId, entityId, _, newQuantity, reason, newVersion, occurredAt) =>
          val updateAction = inventoryComponent.InventoriesDao
            .filter(_.id === entityId.asString)
            .map(r => (r.availableQuantity, r.version, r.updatedAt))
            .update((newQuantity.amount, newVersion.value, Timestamp.from(occurredAt.asInstant())))
          val txRecord = transactionComponent.InventoryTransactionsRecord(
            id = ULID.newULID.toString,
            inventoryId = entityId.asString,
            transactionType = "ADJUSTED",
            quantity = newQuantity.amount,
            fromWarehouseZoneId = None,
            toWarehouseZoneId = None,
            reason = Some(reason),
            occurredAt = Timestamp.from(occurredAt.asInstant()),
            createdAt = Timestamp.from(java.time.Instant.now())
          )
          DBIO.seq(updateAction, transactionComponent.InventoryTransactionsDao.+=(txRecord))
      }

      val result = Await.result(db.run(actions.transactionally), databaseOperationTimeout)
      logger.debug(s"Successfully processed InventoryEvent: ${event.getClass.getSimpleName} for entity: ${event.entityId.asString}")
      Right(())
    } catch {
      case ex: Exception =>
        logger.error(s"Error processing InventoryEvent: ${event.getClass.getSimpleName}", ex)
        Left(ProcessingError(s"Error processing event: ${ex.getMessage}", Some(ex)))
    }
  }

  private case class ProcessingError(message: String, exception: Option[Throwable])

}
