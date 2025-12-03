# 第7部 第5章 複数集約の実装

本章では、共用データ管理サービスの主要な集約をPekko Persistenceを使用してイベントソーシングで実装します。

## 5.1 Product集約の実装

商品集約は、商品マスターの一元管理を担当する集約です。商品情報、価格、調達情報を管理し、変更イベントを各Bounded Contextに配信します。

### 5.1.1 コマンドとイベントの定義

```scala
package com.example.shareddata.command.product

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.pattern.StatusReply
import java.time.{Instant, LocalDate}

// Product コマンド
sealed trait ProductCommand extends CborSerializable

// 商品作成
final case class CreateProduct(
  productId: ProductId,
  productCode: ProductCode,
  productName: ProductName,
  categoryCode: CategoryCode,
  unitOfMeasure: UnitOfMeasure,
  standardCost: Money,
  listPrice: Money,
  primarySupplierId: Option[SupplierId],
  leadTimeDays: LeadTime,
  minimumOrderQuantity: Quantity,
  storageCondition: Option[StorageCondition],
  validFrom: LocalDate,
  createdBy: UserId,
  replyTo: ActorRef[StatusReply[ProductCreated]]
) extends ProductCommand

// 商品情報更新
final case class UpdateProductInfo(
  productId: ProductId,
  productName: Option[ProductName],
  categoryCode: Option[CategoryCode],
  unitOfMeasure: Option[UnitOfMeasure],
  storageCondition: Option[StorageCondition],
  updatedBy: UserId,
  reason: String,
  replyTo: ActorRef[StatusReply[ProductInfoUpdated]]
) extends ProductCommand

// 価格変更
final case class ChangeProductPrice(
  productId: ProductId,
  newListPrice: Money,
  effectiveFrom: LocalDate,
  changedBy: UserId,
  reason: String,
  replyTo: ActorRef[StatusReply[ProductPriceChanged]]
) extends ProductCommand

// 特別価格追加
final case class AddSpecialPrice(
  productId: ProductId,
  customerId: CustomerId,
  unitPrice: Money,
  validFrom: LocalDate,
  validTo: Option[LocalDate],
  createdBy: UserId,
  reason: String,
  replyTo: ActorRef[StatusReply[SpecialPriceAdded]]
) extends ProductCommand

// 主要仕入先変更
final case class ChangePrimarySupplier(
  productId: ProductId,
  supplierId: SupplierId,
  changedBy: UserId,
  reason: String,
  replyTo: ActorRef[StatusReply[PrimarySupplierChanged]]
) extends ProductCommand

// 商品停止
final case class SuspendProduct(
  productId: ProductId,
  suspendedBy: UserId,
  reason: String,
  replyTo: ActorRef[StatusReply[ProductSuspended]]
) extends ProductCommand

// 商品再開
final case class ReactivateProduct(
  productId: ProductId,
  reactivatedBy: UserId,
  reason: String,
  replyTo: ActorRef[StatusReply[ProductReactivated]]
) extends ProductCommand

// 商品廃止
final case class ObsoleteProduct(
  productId: ProductId,
  obsoletedBy: UserId,
  reason: String,
  replyTo: ActorRef[StatusReply[ProductObsoleted]]
) extends ProductCommand

// Product イベント
sealed trait ProductEvent extends CborSerializable

// 商品作成イベント
final case class ProductCreated(
  productId: ProductId,
  productCode: ProductCode,
  productName: ProductName,
  categoryCode: CategoryCode,
  unitOfMeasure: UnitOfMeasure,
  standardCost: Money,
  listPrice: Money,
  primarySupplierId: Option[SupplierId],
  leadTimeDays: LeadTime,
  minimumOrderQuantity: Quantity,
  storageCondition: Option[StorageCondition],
  validFrom: LocalDate,
  createdBy: UserId,
  createdAt: Instant
) extends ProductEvent

// 商品情報更新イベント
final case class ProductInfoUpdated(
  productId: ProductId,
  productName: Option[ProductName],
  categoryCode: Option[CategoryCode],
  unitOfMeasure: Option[UnitOfMeasure],
  storageCondition: Option[StorageCondition],
  updatedBy: UserId,
  updatedAt: Instant,
  reason: String
) extends ProductEvent

// 価格変更イベント
final case class ProductPriceChanged(
  productId: ProductId,
  oldListPrice: Money,
  newListPrice: Money,
  effectiveFrom: LocalDate,
  changedBy: UserId,
  changedAt: Instant,
  reason: String
) extends ProductEvent

// 特別価格追加イベント
final case class SpecialPriceAdded(
  productId: ProductId,
  customerId: CustomerId,
  unitPrice: Money,
  validFrom: LocalDate,
  validTo: Option[LocalDate],
  createdBy: UserId,
  createdAt: Instant,
  reason: String
) extends ProductEvent

// 主要仕入先変更イベント
final case class PrimarySupplierChanged(
  productId: ProductId,
  oldSupplierId: Option[SupplierId],
  newSupplierId: SupplierId,
  changedBy: UserId,
  changedAt: Instant,
  reason: String
) extends ProductEvent

// 商品停止イベント
final case class ProductSuspended(
  productId: ProductId,
  suspendedBy: UserId,
  suspendedAt: Instant,
  reason: String
) extends ProductEvent

// 商品再開イベント
final case class ProductReactivated(
  productId: ProductId,
  reactivatedBy: UserId,
  reactivatedAt: Instant,
  reason: String
) extends ProductEvent

// 商品廃止イベント
final case class ProductObsoleted(
  productId: ProductId,
  obsoletedBy: UserId,
  obsoletedAt: Instant,
  reason: String
) extends ProductEvent
```

### 5.1.2 ProductActor の実装

```scala
package com.example.shareddata.command.product

import org.apache.pekko.actor.typed.{ActorContext, Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, RetentionCriteria}
import org.apache.pekko.pattern.StatusReply
import java.time.Instant

object ProductActor {

  // State
  sealed trait State extends CborSerializable
  case object EmptyState extends State
  final case class ProductState(product: Product) extends State

  def apply(
    productId: ProductId,
    productCodeValidator: ProductCode => Boolean
  ): Behavior[ProductCommand] = {
    Behaviors.setup { context =>
      EventSourcedBehavior[ProductCommand, ProductEvent, State](
        persistenceId = PersistenceId.ofUniqueId(s"Product-${productId.value}"),
        emptyState = EmptyState,
        commandHandler = commandHandler(productCodeValidator, context),
        eventHandler = eventHandler
      ).withRetention(RetentionCriteria.snapshotEvery(numberOfEvents = 100, keepNSnapshots = 2))
    }
  }

  // Command Handler
  private def commandHandler(
    productCodeValidator: ProductCode => Boolean,
    context: ActorContext[ProductCommand]
  ): (State, ProductCommand) => Effect[ProductEvent, State] = {

    // 商品作成
    case (EmptyState, cmd: CreateProduct) =>
      // 商品コードの一意性チェック
      if (!productCodeValidator(cmd.productCode)) {
        Effect.none.thenRun { _ =>
          cmd.replyTo ! StatusReply.Error(s"商品コード ${cmd.productCode.value} は既に使用されています")
        }
      } else if (cmd.standardCost.amount > cmd.listPrice.amount) {
        Effect.none.thenRun { _ =>
          cmd.replyTo ! StatusReply.Error("標準原価が定価を超えています")
        }
      } else if (cmd.standardCost.amount <= 0 || cmd.listPrice.amount <= 0) {
        Effect.none.thenRun { _ =>
          cmd.replyTo ! StatusReply.Error("価格は0より大きい値でなければなりません")
        }
      } else {
        val event = ProductCreated(
          productId = cmd.productId,
          productCode = cmd.productCode,
          productName = cmd.productName,
          categoryCode = cmd.categoryCode,
          unitOfMeasure = cmd.unitOfMeasure,
          standardCost = cmd.standardCost,
          listPrice = cmd.listPrice,
          primarySupplierId = cmd.primarySupplierId,
          leadTimeDays = cmd.leadTimeDays,
          minimumOrderQuantity = cmd.minimumOrderQuantity,
          storageCondition = cmd.storageCondition,
          validFrom = cmd.validFrom,
          createdBy = cmd.createdBy,
          createdAt = Instant.now()
        )

        Effect.persist(event).thenRun { _ =>
          cmd.replyTo ! StatusReply.Success(event)
          context.log.info("商品が作成されました: {}", cmd.productCode.value)
        }
      }

    case (_: ProductState, _: CreateProduct) =>
      Effect.none.thenRun { (_, cmd: CreateProduct) =>
        cmd.replyTo ! StatusReply.Error("商品は既に存在します")
      }

    // 商品情報更新
    case (ProductState(product), cmd: UpdateProductInfo) =>
      if (product.status == ProductStatus.Obsolete) {
        Effect.none.thenRun { _ =>
          cmd.replyTo ! StatusReply.Error("廃止済みの商品は更新できません")
        }
      } else {
        val event = ProductInfoUpdated(
          productId = cmd.productId,
          productName = cmd.productName,
          categoryCode = cmd.categoryCode,
          unitOfMeasure = cmd.unitOfMeasure,
          storageCondition = cmd.storageCondition,
          updatedBy = cmd.updatedBy,
          updatedAt = Instant.now(),
          reason = cmd.reason
        )

        Effect.persist(event).thenRun { _ =>
          cmd.replyTo ! StatusReply.Success(event)
          context.log.info("商品情報が更新されました: {}", product.productCode.value)
        }
      }

    case (EmptyState, cmd: UpdateProductInfo) =>
      Effect.none.thenRun { _ =>
        cmd.replyTo ! StatusReply.Error("商品が存在しません")
      }

    // 価格変更
    case (ProductState(product), cmd: ChangeProductPrice) =>
      product.changePrice(cmd.newListPrice, cmd.effectiveFrom) match {
        case Left(error) =>
          Effect.none.thenRun { _ =>
            cmd.replyTo ! StatusReply.Error(error.message)
          }

        case Right(_) =>
          val event = ProductPriceChanged(
            productId = cmd.productId,
            oldListPrice = product.listPrice,
            newListPrice = cmd.newListPrice,
            effectiveFrom = cmd.effectiveFrom,
            changedBy = cmd.changedBy,
            changedAt = Instant.now(),
            reason = cmd.reason
          )

          Effect.persist(event).thenRun { _ =>
            cmd.replyTo ! StatusReply.Success(event)
            context.log.info(
              "商品価格が変更されました: {} ({}円 → {}円)",
              product.productCode.value,
              product.listPrice.amount,
              cmd.newListPrice.amount
            )
          }
      }

    case (EmptyState, cmd: ChangeProductPrice) =>
      Effect.none.thenRun { _ =>
        cmd.replyTo ! StatusReply.Error("商品が存在しません")
      }

    // 特別価格追加
    case (ProductState(product), cmd: AddSpecialPrice) =>
      product.addSpecialPrice(
        cmd.customerId,
        cmd.unitPrice,
        cmd.validFrom,
        cmd.validTo
      ) match {
        case Left(error) =>
          Effect.none.thenRun { _ =>
            cmd.replyTo ! StatusReply.Error(error.message)
          }

        case Right(_) =>
          val event = SpecialPriceAdded(
            productId = cmd.productId,
            customerId = cmd.customerId,
            unitPrice = cmd.unitPrice,
            validFrom = cmd.validFrom,
            validTo = cmd.validTo,
            createdBy = cmd.createdBy,
            createdAt = Instant.now(),
            reason = cmd.reason
          )

          Effect.persist(event).thenRun { _ =>
            cmd.replyTo ! StatusReply.Success(event)
            context.log.info(
              "特別価格が追加されました: {} 顧客={} 価格={}円",
              product.productCode.value,
              cmd.customerId.value,
              cmd.unitPrice.amount
            )
          }
      }

    case (EmptyState, cmd: AddSpecialPrice) =>
      Effect.none.thenRun { _ =>
        cmd.replyTo ! StatusReply.Error("商品が存在しません")
      }

    // 主要仕入先変更
    case (ProductState(product), cmd: ChangePrimarySupplier) =>
      val event = PrimarySupplierChanged(
        productId = cmd.productId,
        oldSupplierId = product.primarySupplierId,
        newSupplierId = cmd.supplierId,
        changedBy = cmd.changedBy,
        changedAt = Instant.now(),
        reason = cmd.reason
      )

      Effect.persist(event).thenRun { _ =>
        cmd.replyTo ! StatusReply.Success(event)
        context.log.info(
          "主要仕入先が変更されました: {} ({})",
          product.productCode.value,
          cmd.supplierId.value
        )
      }

    case (EmptyState, cmd: ChangePrimarySupplier) =>
      Effect.none.thenRun { _ =>
        cmd.replyTo ! StatusReply.Error("商品が存在しません")
      }

    // 商品停止
    case (ProductState(product), cmd: SuspendProduct) =>
      product.suspend() match {
        case Left(error) =>
          Effect.none.thenRun { _ =>
            cmd.replyTo ! StatusReply.Error(error.message)
          }

        case Right(_) =>
          val event = ProductSuspended(
            productId = cmd.productId,
            suspendedBy = cmd.suspendedBy,
            suspendedAt = Instant.now(),
            reason = cmd.reason
          )

          Effect.persist(event).thenRun { _ =>
            cmd.replyTo ! StatusReply.Success(event)
            context.log.info("商品が停止されました: {}", product.productCode.value)
          }
      }

    case (EmptyState, cmd: SuspendProduct) =>
      Effect.none.thenRun { _ =>
        cmd.replyTo ! StatusReply.Error("商品が存在しません")
      }

    // 商品再開
    case (ProductState(product), cmd: ReactivateProduct) =>
      product.reactivate() match {
        case Left(error) =>
          Effect.none.thenRun { _ =>
            cmd.replyTo ! StatusReply.Error(error.message)
          }

        case Right(_) =>
          val event = ProductReactivated(
            productId = cmd.productId,
            reactivatedBy = cmd.reactivatedBy,
            reactivatedAt = Instant.now(),
            reason = cmd.reason
          )

          Effect.persist(event).thenRun { _ =>
            cmd.replyTo ! StatusReply.Success(event)
            context.log.info("商品が再開されました: {}", product.productCode.value)
          }
      }

    case (EmptyState, cmd: ReactivateProduct) =>
      Effect.none.thenRun { _ =>
        cmd.replyTo ! StatusReply.Error("商品が存在しません")
      }

    // 商品廃止
    case (ProductState(product), cmd: ObsoleteProduct) =>
      product.obsolete() match {
        case Left(error) =>
          Effect.none.thenRun { _ =>
            cmd.replyTo ! StatusReply.Error(error.message)
          }

        case Right(_) =>
          val event = ProductObsoleted(
            productId = cmd.productId,
            obsoletedBy = cmd.obsoletedBy,
            obsoletedAt = Instant.now(),
            reason = cmd.reason
          )

          Effect.persist(event).thenRun { _ =>
            cmd.replyTo ! StatusReply.Success(event)
            context.log.info("商品が廃止されました: {}", product.productCode.value)
          }
      }

    case (EmptyState, cmd: ObsoleteProduct) =>
      Effect.none.thenRun { _ =>
        cmd.replyTo ! StatusReply.Error("商品が存在しません")
      }
  }

  // Event Handler
  private val eventHandler: (State, ProductEvent) => State = {
    // 商品作成
    case (EmptyState, event: ProductCreated) =>
      val product = Product(
        id = event.productId,
        productCode = event.productCode,
        productName = event.productName,
        categoryCode = event.categoryCode,
        unitOfMeasure = event.unitOfMeasure,
        standardCost = event.standardCost,
        listPrice = event.listPrice,
        primarySupplierId = event.primarySupplierId,
        leadTimeDays = event.leadTimeDays,
        minimumOrderQuantity = event.minimumOrderQuantity,
        storageCondition = event.storageCondition,
        status = ProductStatus.Active,
        validPeriod = ValidPeriod(event.validFrom, None),
        prices = List(ProductPrice(
          priceType = PriceType.Standard,
          customerId = None,
          unitPrice = event.listPrice,
          validPeriod = ValidPeriod(event.validFrom, None)
        )),
        version = Version.initial
      )
      ProductState(product)

    // 商品情報更新
    case (ProductState(product), event: ProductInfoUpdated) =>
      val updatedProduct = product.updateInfo(
        event.productName,
        event.categoryCode,
        event.unitOfMeasure,
        event.storageCondition
      )
      ProductState(updatedProduct.copy(version = product.version.increment))

    // 価格変更
    case (ProductState(product), event: ProductPriceChanged) =>
      // 既存の標準価格の有効終了日を設定
      val updatedPrices = product.prices.map { p =>
        if (p.priceType == PriceType.Standard && p.validPeriod.validTo.isEmpty) {
          p.copy(validPeriod = p.validPeriod.copy(validTo = Some(event.effectiveFrom.minusDays(1))))
        } else {
          p
        }
      }

      // 新しい標準価格を追加
      val newPrice = ProductPrice(
        priceType = PriceType.Standard,
        customerId = None,
        unitPrice = event.newListPrice,
        validPeriod = ValidPeriod(event.effectiveFrom, None)
      )

      val updatedProduct = product.copy(
        listPrice = event.newListPrice,
        prices = newPrice :: updatedPrices,
        version = product.version.increment
      )
      ProductState(updatedProduct)

    // 特別価格追加
    case (ProductState(product), event: SpecialPriceAdded) =>
      val newPrice = ProductPrice(
        priceType = PriceType.Special,
        customerId = Some(event.customerId),
        unitPrice = event.unitPrice,
        validPeriod = ValidPeriod(event.validFrom, event.validTo)
      )

      val updatedProduct = product.copy(
        prices = newPrice :: product.prices,
        version = product.version.increment
      )
      ProductState(updatedProduct)

    // 主要仕入先変更
    case (ProductState(product), event: PrimarySupplierChanged) =>
      val updatedProduct = product.copy(
        primarySupplierId = Some(event.newSupplierId),
        version = product.version.increment
      )
      ProductState(updatedProduct)

    // 商品停止
    case (ProductState(product), _: ProductSuspended) =>
      val updatedProduct = product.copy(
        status = ProductStatus.Suspended,
        version = product.version.increment
      )
      ProductState(updatedProduct)

    // 商品再開
    case (ProductState(product), _: ProductReactivated) =>
      val updatedProduct = product.copy(
        status = ProductStatus.Active,
        version = product.version.increment
      )
      ProductState(updatedProduct)

    // 商品廃止
    case (ProductState(product), event: ProductObsoleted) =>
      val updatedProduct = product.copy(
        status = ProductStatus.Obsolete,
        validPeriod = product.validPeriod.copy(validTo = Some(event.obsoletedAt.atZone(java.time.ZoneId.systemDefault()).toLocalDate())),
        version = product.version.increment
      )
      ProductState(updatedProduct)

    case (state, _) => state
  }
}
```

### 5.1.3 ビジネスルールの実装

```scala
package com.example.shareddata.command.product

// 商品コードの一意性チェック
trait ProductCodeValidator {
  def isUnique(productCode: ProductCode): Boolean
}

class ProductCodeValidatorImpl(productRepository: ProductReadModelRepository) extends ProductCodeValidator {
  override def isUnique(productCode: ProductCode): Boolean = {
    // Read Modelで商品コードの存在チェック
    productRepository.findByProductCode(productCode) match {
      case Some(_) => false  // 既に存在する
      case None => true      // 一意
    }
  }
}

// 価格変更の検証
object PriceChangeValidator {
  def validate(
    currentPrice: Money,
    newPrice: Money,
    effectiveFrom: LocalDate
  ): Either[ValidationError, Unit] = {
    for {
      _ <- validatePositivePrice(newPrice)
      _ <- validateEffectiveDate(effectiveFrom)
      _ <- validatePriceChange(currentPrice, newPrice)
    } yield ()
  }

  private def validatePositivePrice(price: Money): Either[ValidationError, Unit] = {
    if (price.amount <= 0) {
      Left(ValidationError("価格は0より大きい値でなければなりません"))
    } else {
      Right(())
    }
  }

  private def validateEffectiveDate(date: LocalDate): Either[ValidationError, Unit] = {
    if (date.isBefore(LocalDate.now())) {
      Left(ValidationError("有効開始日は現在日以降でなければなりません"))
    } else {
      Right(())
    }
  }

  private def validatePriceChange(currentPrice: Money, newPrice: Money): Either[ValidationError, Unit] = {
    val changeRate = ((newPrice.amount - currentPrice.amount) / currentPrice.amount * 100).abs

    // 50%を超える価格変更は警告
    if (changeRate > 50) {
      Left(ValidationError(s"価格変更率が${changeRate.setScale(1, BigDecimal.RoundingMode.HALF_UP)}%です。確認してください。"))
    } else {
      Right(())
    }
  }
}
```

## 5.2 AccountSubject集約の実装

勘定科目集約は、会計システムで使用される勘定科目体系を管理する集約です。

### 5.2.1 コマンドとイベントの定義

```scala
package com.example.shareddata.command.accountsubject

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.pattern.StatusReply
import java.time.{Instant, LocalDate}

// AccountSubject コマンド
sealed trait AccountSubjectCommand extends CborSerializable

// 勘定科目作成
final case class CreateAccountSubject(
  accountSubjectId: AccountSubjectId,
  accountCode: AccountCode,
  accountName: AccountName,
  accountType: AccountType,
  accountSubtype: Option[AccountSubtype],
  balanceSide: BalanceSide,
  parentAccountId: Option[AccountSubjectId],
  level: Int,
  displayOrder: Int,
  requiresAuxiliary: Boolean,
  auxiliaryType: Option[AuxiliaryType],
  validFrom: LocalDate,
  createdBy: UserId,
  replyTo: ActorRef[StatusReply[AccountSubjectCreated]]
) extends AccountSubjectCommand

// 勘定科目名変更
final case class UpdateAccountSubjectName(
  accountSubjectId: AccountSubjectId,
  newAccountName: AccountName,
  updatedBy: UserId,
  reason: String,
  replyTo: ActorRef[StatusReply[AccountSubjectNameUpdated]]
) extends AccountSubjectCommand

// 親勘定科目変更
final case class ChangeParentAccount(
  accountSubjectId: AccountSubjectId,
  newParentAccountId: AccountSubjectId,
  newLevel: Int,
  changedBy: UserId,
  reason: String,
  replyTo: ActorRef[StatusReply[ParentAccountChanged]]
) extends AccountSubjectCommand

// 勘定科目廃止
final case class ObsoleteAccountSubject(
  accountSubjectId: AccountSubjectId,
  obsoletedBy: UserId,
  reason: String,
  replyTo: ActorRef[StatusReply[AccountSubjectObsoleted]]
) extends AccountSubjectCommand

// AccountSubject イベント
sealed trait AccountSubjectEvent extends CborSerializable

// 勘定科目作成イベント
final case class AccountSubjectCreated(
  accountSubjectId: AccountSubjectId,
  accountCode: AccountCode,
  accountName: AccountName,
  accountType: AccountType,
  accountSubtype: Option[AccountSubtype],
  balanceSide: BalanceSide,
  parentAccountId: Option[AccountSubjectId],
  level: Int,
  displayOrder: Int,
  requiresAuxiliary: Boolean,
  auxiliaryType: Option[AuxiliaryType],
  validFrom: LocalDate,
  createdBy: UserId,
  createdAt: Instant
) extends AccountSubjectEvent

// 勘定科目名更新イベント
final case class AccountSubjectNameUpdated(
  accountSubjectId: AccountSubjectId,
  oldAccountName: AccountName,
  newAccountName: AccountName,
  updatedBy: UserId,
  updatedAt: Instant,
  reason: String
) extends AccountSubjectEvent

// 親勘定科目変更イベント
final case class ParentAccountChanged(
  accountSubjectId: AccountSubjectId,
  oldParentAccountId: Option[AccountSubjectId],
  newParentAccountId: AccountSubjectId,
  oldLevel: Int,
  newLevel: Int,
  changedBy: UserId,
  changedAt: Instant,
  reason: String
) extends AccountSubjectEvent

// 勘定科目廃止イベント
final case class AccountSubjectObsoleted(
  accountSubjectId: AccountSubjectId,
  obsoletedBy: UserId,
  obsoletedAt: Instant,
  reason: String
) extends AccountSubjectEvent
```

### 5.2.2 AccountSubjectActor の実装

```scala
package com.example.shareddata.command.accountsubject

import org.apache.pekko.actor.typed.{ActorContext, Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, RetentionCriteria}
import org.apache.pekko.pattern.StatusReply
import java.time.Instant

object AccountSubjectActor {

  // State
  sealed trait State extends CborSerializable
  case object EmptyState extends State
  final case class AccountSubjectState(accountSubject: AccountSubject) extends State

  def apply(
    accountSubjectId: AccountSubjectId,
    accountCodeValidator: AccountCode => Boolean,
    usageChecker: AccountSubjectId => Boolean
  ): Behavior[AccountSubjectCommand] = {
    Behaviors.setup { context =>
      EventSourcedBehavior[AccountSubjectCommand, AccountSubjectEvent, State](
        persistenceId = PersistenceId.ofUniqueId(s"AccountSubject-${accountSubjectId.value}"),
        emptyState = EmptyState,
        commandHandler = commandHandler(accountCodeValidator, usageChecker, context),
        eventHandler = eventHandler
      ).withRetention(RetentionCriteria.snapshotEvery(numberOfEvents = 50, keepNSnapshots = 2))
    }
  }

  // Command Handler
  private def commandHandler(
    accountCodeValidator: AccountCode => Boolean,
    usageChecker: AccountSubjectId => Boolean,
    context: ActorContext[AccountSubjectCommand]
  ): (State, AccountSubjectCommand) => Effect[AccountSubjectEvent, State] = {

    // 勘定科目作成
    case (EmptyState, cmd: CreateAccountSubject) =>
      // 勘定科目コードの一意性チェック
      if (!accountCodeValidator(cmd.accountCode)) {
        Effect.none.thenRun { _ =>
          cmd.replyTo ! StatusReply.Error(s"勘定科目コード ${cmd.accountCode.value} は既に使用されています")
        }
      } else if (cmd.level < 1 || cmd.level > 4) {
        Effect.none.thenRun { _ =>
          cmd.replyTo ! StatusReply.Error("階層レベルは1から4の範囲でなければなりません")
        }
      } else if ((cmd.level == 1 && cmd.parentAccountId.isDefined) || (cmd.level > 1 && cmd.parentAccountId.isEmpty)) {
        Effect.none.thenRun { _ =>
          cmd.replyTo ! StatusReply.Error("レベル1の科目は親を持たず、レベル2以降の科目は親を持つ必要があります")
        }
      } else {
        // 貸借区分の整合性チェック
        val expectedBalanceSide = cmd.accountType match {
          case AccountType.Asset | AccountType.Expense => BalanceSide.Debit
          case AccountType.Liability | AccountType.Equity | AccountType.Revenue => BalanceSide.Credit
        }

        if (cmd.balanceSide != expectedBalanceSide) {
          Effect.none.thenRun { _ =>
            cmd.replyTo ! StatusReply.Error(
              s"勘定科目タイプ${cmd.accountType}の貸借区分は${expectedBalanceSide}でなければなりません"
            )
          }
        } else {
          val event = AccountSubjectCreated(
            accountSubjectId = cmd.accountSubjectId,
            accountCode = cmd.accountCode,
            accountName = cmd.accountName,
            accountType = cmd.accountType,
            accountSubtype = cmd.accountSubtype,
            balanceSide = cmd.balanceSide,
            parentAccountId = cmd.parentAccountId,
            level = cmd.level,
            displayOrder = cmd.displayOrder,
            requiresAuxiliary = cmd.requiresAuxiliary,
            auxiliaryType = cmd.auxiliaryType,
            validFrom = cmd.validFrom,
            createdBy = cmd.createdBy,
            createdAt = Instant.now()
          )

          Effect.persist(event).thenRun { _ =>
            cmd.replyTo ! StatusReply.Success(event)
            context.log.info("勘定科目が作成されました: {}", cmd.accountCode.value)
          }
        }
      }

    case (_: AccountSubjectState, _: CreateAccountSubject) =>
      Effect.none.thenRun { (_, cmd: CreateAccountSubject) =>
        cmd.replyTo ! StatusReply.Error("勘定科目は既に存在します")
      }

    // 勘定科目名変更
    case (AccountSubjectState(accountSubject), cmd: UpdateAccountSubjectName) =>
      if (accountSubject.status == AccountSubjectStatus.Obsolete) {
        Effect.none.thenRun { _ =>
          cmd.replyTo ! StatusReply.Error("廃止済みの勘定科目は更新できません")
        }
      } else {
        val event = AccountSubjectNameUpdated(
          accountSubjectId = cmd.accountSubjectId,
          oldAccountName = accountSubject.accountName,
          newAccountName = cmd.newAccountName,
          updatedBy = cmd.updatedBy,
          updatedAt = Instant.now(),
          reason = cmd.reason
        )

        Effect.persist(event).thenRun { _ =>
          cmd.replyTo ! StatusReply.Success(event)
          context.log.info(
            "勘定科目名が変更されました: {} ({} → {})",
            accountSubject.accountCode.value,
            accountSubject.accountName.value,
            cmd.newAccountName.value
          )
        }
      }

    case (EmptyState, cmd: UpdateAccountSubjectName) =>
      Effect.none.thenRun { _ =>
        cmd.replyTo ! StatusReply.Error("勘定科目が存在しません")
      }

    // 親勘定科目変更
    case (AccountSubjectState(accountSubject), cmd: ChangeParentAccount) =>
      accountSubject.changeParent(cmd.newParentAccountId, cmd.newLevel) match {
        case Left(error) =>
          Effect.none.thenRun { _ =>
            cmd.replyTo ! StatusReply.Error(error.message)
          }

        case Right(_) =>
          val event = ParentAccountChanged(
            accountSubjectId = cmd.accountSubjectId,
            oldParentAccountId = accountSubject.parentAccountId,
            newParentAccountId = cmd.newParentAccountId,
            oldLevel = accountSubject.level,
            newLevel = cmd.newLevel,
            changedBy = cmd.changedBy,
            changedAt = Instant.now(),
            reason = cmd.reason
          )

          Effect.persist(event).thenRun { _ =>
            cmd.replyTo ! StatusReply.Success(event)
            context.log.info(
              "親勘定科目が変更されました: {} (レベル{} → レベル{})",
              accountSubject.accountCode.value,
              accountSubject.level,
              cmd.newLevel
            )
          }
      }

    case (EmptyState, cmd: ChangeParentAccount) =>
      Effect.none.thenRun { _ =>
        cmd.replyTo ! StatusReply.Error("勘定科目が存在しません")
      }

    // 勘定科目廃止
    case (AccountSubjectState(accountSubject), cmd: ObsoleteAccountSubject) =>
      // 使用中チェック（仕訳が存在するか）
      if (usageChecker(cmd.accountSubjectId)) {
        Effect.none.thenRun { _ =>
          cmd.replyTo ! StatusReply.Error("使用中の勘定科目は廃止できません（仕訳が存在します）")
        }
      } else {
        accountSubject.obsolete() match {
          case Left(error) =>
            Effect.none.thenRun { _ =>
              cmd.replyTo ! StatusReply.Error(error.message)
            }

          case Right(_) =>
            val event = AccountSubjectObsoleted(
              accountSubjectId = cmd.accountSubjectId,
              obsoletedBy = cmd.obsoletedBy,
              obsoletedAt = Instant.now(),
              reason = cmd.reason
            )

            Effect.persist(event).thenRun { _ =>
              cmd.replyTo ! StatusReply.Success(event)
              context.log.info("勘定科目が廃止されました: {}", accountSubject.accountCode.value)
            }
        }
      }

    case (EmptyState, cmd: ObsoleteAccountSubject) =>
      Effect.none.thenRun { _ =>
        cmd.replyTo ! StatusReply.Error("勘定科目が存在しません")
      }
  }

  // Event Handler
  private val eventHandler: (State, AccountSubjectEvent) => State = {
    // 勘定科目作成
    case (EmptyState, event: AccountSubjectCreated) =>
      val accountSubject = AccountSubject(
        id = event.accountSubjectId,
        accountCode = event.accountCode,
        accountName = event.accountName,
        accountType = event.accountType,
        accountSubtype = event.accountSubtype,
        balanceSide = event.balanceSide,
        parentAccountId = event.parentAccountId,
        level = event.level,
        displayOrder = event.displayOrder,
        requiresAuxiliary = event.requiresAuxiliary,
        auxiliaryType = event.auxiliaryType,
        status = AccountSubjectStatus.Active,
        validPeriod = ValidPeriod(event.validFrom, None),
        version = Version.initial
      )
      AccountSubjectState(accountSubject)

    // 勘定科目名変更
    case (AccountSubjectState(accountSubject), event: AccountSubjectNameUpdated) =>
      val updatedAccountSubject = accountSubject.copy(
        accountName = event.newAccountName,
        version = accountSubject.version.increment
      )
      AccountSubjectState(updatedAccountSubject)

    // 親勘定科目変更
    case (AccountSubjectState(accountSubject), event: ParentAccountChanged) =>
      val updatedAccountSubject = accountSubject.copy(
        parentAccountId = Some(event.newParentAccountId),
        level = event.newLevel,
        version = accountSubject.version.increment
      )
      AccountSubjectState(updatedAccountSubject)

    // 勘定科目廃止
    case (AccountSubjectState(accountSubject), event: AccountSubjectObsoleted) =>
      val updatedAccountSubject = accountSubject.copy(
        status = AccountSubjectStatus.Obsolete,
        validPeriod = accountSubject.validPeriod.copy(
          validTo = Some(event.obsoletedAt.atZone(java.time.ZoneId.systemDefault()).toLocalDate())
        ),
        version = accountSubject.version.increment
      )
      AccountSubjectState(updatedAccountSubject)

    case (state, _) => state
  }
}
```

### 5.2.3 ビジネスルールの実装

```scala
package com.example.shareddata.command.accountsubject

// 勘定科目コードの一意性チェック
trait AccountCodeValidator {
  def isUnique(accountCode: AccountCode): Boolean
}

class AccountCodeValidatorImpl(accountSubjectRepository: AccountSubjectReadModelRepository)
  extends AccountCodeValidator {
  override def isUnique(accountCode: AccountCode): Boolean = {
    accountSubjectRepository.findByAccountCode(accountCode) match {
      case Some(_) => false
      case None => true
    }
  }
}

// 勘定科目の使用チェック
trait AccountSubjectUsageChecker {
  def isInUse(accountSubjectId: AccountSubjectId): Boolean
}

class AccountSubjectUsageCheckerImpl(journalEntryRepository: JournalEntryReadModelRepository)
  extends AccountSubjectUsageChecker {
  override def isInUse(accountSubjectId: AccountSubjectId): Boolean = {
    // 仕訳に使用されているかチェック
    journalEntryRepository.existsByAccountSubjectId(accountSubjectId)
  }
}
```

## 5.3 まとめ

本章では、Product集約とAccountSubject集約をPekko Persistenceで実装しました。

**Product集約の実装**:
- 8つのコマンド：Create, UpdateInfo, ChangePrice, AddSpecialPrice, ChangePrimarySupplier, Suspend, Reactivate, Obsolete
- 8つのイベント：対応するコマンドごとのイベント
- ビジネスルール：商品コードの一意性、価格の妥当性、有効期間の重複チェック
- 状態管理：EmptyState → ProductState（商品作成後）
- イベントハンドラー：イベントから状態への変換ロジック

**AccountSubject集約の実装**:
- 4つのコマンド：Create, UpdateName, ChangeParent, Obsolete
- 4つのイベント：対応するコマンドごとのイベント
- ビジネスルール：勘定科目コードの一意性、階層構造の整合性、貸借区分の検証、使用中の勘定科目は廃止不可
- 状態管理：EmptyState → AccountSubjectState（勘定科目作成後）
- イベントハンドラー：イベントから状態への変換ロジック

**共通の実装パターン**:
- EventSourcedBehavior：Pekko Persistenceの型付きアクター
- StatusReply：コマンド実行結果の返却
- バリデーション：コマンドハンドラーでのビジネスルール検証
- スナップショット：一定イベント数ごとの状態保存
- バージョン管理：楽観的ロックによる競合検出

次章では、これらの集約の変更イベントを各Bounded Contextに伝播するイベント駆動マスターデータ同期を実装します。
