package io.github.j5ik2o.pcqrses.readModelUpdater

import com.amazonaws.services.lambda.runtime.events.DynamodbEvent
import com.amazonaws.services.lambda.runtime.events.DynamodbEvent.DynamodbStreamRecord
import com.amazonaws.services.lambda.runtime.{Context, RequestHandler}
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.typesafe.config.ConfigFactory
import io.github.j5ik2o.pcqrses.command.domain.users.UserAccountEvent
import io.github.j5ik2o.pcqrses.query.interfaceAdapter.dao.UserAccountsComponent
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

      if (persistenceIdOpt.isEmpty || !persistenceIdOpt.get.startsWith(UserAccountEntityTypePrefix)) {
        logger.debug(s"Skipping record with persistence-id: ${persistenceIdOpt.getOrElse("null")}")
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

  private case class ProcessingError(message: String, exception: Option[Throwable])

}
