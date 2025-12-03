# 第7部 第4章 ドメインモデルの設計

本章では、共用データ管理サービスのドメインモデルをDDD（ドメイン駆動設計）に基づいて設計します。

## 4.1 Product集約（商品）

商品マスターは共用データ管理サービスの中核となる集約です。商品情報、価格、調達情報を管理し、各Bounded Contextに変更を伝播します。

### 4.1.1 Product エンティティ

```scala
package com.example.shareddata.domain.model.product

import java.time.LocalDate
import java.util.UUID

// 商品エンティティ
final case class Product(
  id: ProductId,
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
  status: ProductStatus,
  validPeriod: ValidPeriod,
  prices: List[ProductPrice],
  version: Version
) {

  // 指定日時点での標準価格を取得
  def standardPriceAt(date: LocalDate): Option[Money] = {
    prices
      .filter(p => p.priceType == PriceType.Standard && p.validPeriod.isValidAt(date))
      .sortBy(_.validPeriod.validFrom)(Ordering[LocalDate].reverse)
      .headOption
      .map(_.unitPrice)
  }

  // 顧客別の特別価格を取得
  def specialPriceFor(customerId: CustomerId, date: LocalDate): Option[Money] = {
    prices
      .filter { p =>
        p.priceType == PriceType.Special &&
        p.customerId.contains(customerId) &&
        p.validPeriod.isValidAt(date)
      }
      .sortBy(_.validPeriod.validFrom)(Ordering[LocalDate].reverse)
      .headOption
      .map(_.unitPrice)
  }

  // 指定日時点での有効な価格を取得（特別価格 > 標準価格の優先順）
  def effectivePriceFor(customerId: Option[CustomerId], date: LocalDate): Option[Money] = {
    customerId.flatMap(specialPriceFor(_, date))
      .orElse(standardPriceAt(date))
  }

  // 商品が指定日時点で有効か
  def isValidAt(date: LocalDate): Boolean = {
    status == ProductStatus.Active && validPeriod.isValidAt(date)
  }

  // 商品情報を更新
  def updateInfo(
    newProductName: Option[ProductName],
    newCategoryCode: Option[CategoryCode],
    newUnitOfMeasure: Option[UnitOfMeasure],
    newStorageCondition: Option[StorageCondition]
  ): Product = {
    copy(
      productName = newProductName.getOrElse(productName),
      categoryCode = newCategoryCode.getOrElse(categoryCode),
      unitOfMeasure = newUnitOfMeasure.getOrElse(unitOfMeasure),
      storageCondition = newStorageCondition.orElse(storageCondition)
    )
  }

  // 価格を変更
  def changePrice(newListPrice: Money, effectiveFrom: LocalDate): Either[DomainError, Product] = {
    if (newListPrice.amount <= 0) {
      Left(DomainError("価格は0より大きい値でなければなりません"))
    } else if (effectiveFrom.isBefore(LocalDate.now())) {
      Left(DomainError("価格の有効開始日は現在日以降でなければなりません"))
    } else {
      val newPrice = ProductPrice(
        priceType = PriceType.Standard,
        customerId = None,
        unitPrice = newListPrice,
        validPeriod = ValidPeriod(effectiveFrom, None)
      )

      // 既存の標準価格の有効終了日を設定
      val updatedPrices = prices.map { p =>
        if (p.priceType == PriceType.Standard && p.validPeriod.validTo.isEmpty) {
          p.copy(validPeriod = p.validPeriod.copy(validTo = Some(effectiveFrom.minusDays(1))))
        } else {
          p
        }
      }

      Right(copy(
        listPrice = newListPrice,
        prices = newPrice :: updatedPrices
      ))
    }
  }

  // 特別価格を追加
  def addSpecialPrice(
    customerId: CustomerId,
    unitPrice: Money,
    validFrom: LocalDate,
    validTo: Option[LocalDate]
  ): Either[DomainError, Product] = {
    val newPeriod = ValidPeriod(validFrom, validTo)

    // 同一顧客の特別価格で有効期間が重複していないかチェック
    val overlapping = prices.exists { p =>
      p.priceType == PriceType.Special &&
      p.customerId.contains(customerId) &&
      p.validPeriod.overlaps(newPeriod)
    }

    if (overlapping) {
      Left(DomainError(s"顧客${customerId.value}の特別価格の有効期間が既存の価格と重複しています"))
    } else if (unitPrice.amount <= 0) {
      Left(DomainError("価格は0より大きい値でなければなりません"))
    } else {
      val newPrice = ProductPrice(
        priceType = PriceType.Special,
        customerId = Some(customerId),
        unitPrice = unitPrice,
        validPeriod = newPeriod
      )
      Right(copy(prices = newPrice :: prices))
    }
  }

  // 主要仕入先を変更
  def changePrimarySupplier(supplierId: SupplierId): Product = {
    copy(primarySupplierId = Some(supplierId))
  }

  // 商品を停止
  def suspend(): Either[DomainError, Product] = {
    status match {
      case ProductStatus.Active =>
        Right(copy(status = ProductStatus.Suspended))
      case ProductStatus.Obsolete =>
        Left(DomainError("廃止済みの商品は停止できません"))
      case _ =>
        Left(DomainError(s"現在のステータス${status}では停止できません"))
    }
  }

  // 商品を再開
  def reactivate(): Either[DomainError, Product] = {
    status match {
      case ProductStatus.Suspended =>
        Right(copy(status = ProductStatus.Active))
      case ProductStatus.Obsolete =>
        Left(DomainError("廃止済みの商品は再開できません"))
      case _ =>
        Left(DomainError(s"現在のステータス${status}では再開できません"))
    }
  }

  // 商品を廃止
  def obsolete(): Either[DomainError, Product] = {
    status match {
      case ProductStatus.Obsolete =>
        Left(DomainError("既に廃止済みの商品です"))
      case _ =>
        Right(copy(
          status = ProductStatus.Obsolete,
          validPeriod = validPeriod.copy(validTo = Some(LocalDate.now()))
        ))
    }
  }

  // 商品の検証
  def validate(): Either[ValidationError, Product] = {
    for {
      _ <- validatePrices()
      _ <- validateCosts()
      _ <- validateLeadTime()
    } yield this
  }

  private def validatePrices(): Either[ValidationError, Unit] = {
    if (standardCost.amount > listPrice.amount) {
      Left(ValidationError("標準原価が定価を超えています"))
    } else if (standardCost.amount <= 0 || listPrice.amount <= 0) {
      Left(ValidationError("価格は0より大きい値でなければなりません"))
    } else {
      Right(())
    }
  }

  private def validateCosts(): Either[ValidationError, Unit] = {
    if (minimumOrderQuantity.value <= 0) {
      Left(ValidationError("最小発注数量は0より大きい値でなければなりません"))
    } else {
      Right(())
    }
  }

  private def validateLeadTime(): Either[ValidationError, Unit] = {
    if (leadTimeDays.days < 0) {
      Left(ValidationError("リードタイムは0以上でなければなりません"))
    } else {
      Right(())
    }
  }
}
```

### 4.1.2 値オブジェクト

```scala
// 商品ID
final case class ProductId(value: String) extends AnyVal {
  override def toString: String = value
}

object ProductId {
  def generate(): ProductId = ProductId(UUID.randomUUID().toString)
}

// 商品コード
final case class ProductCode(value: String) extends AnyVal {
  require(value.matches("^P-\\d{3,}$"), "商品コードの形式が不正です（例: P-001）")
}

// 商品名
final case class ProductName(value: String) extends AnyVal {
  require(value.nonEmpty && value.length <= 200, "商品名は1文字以上200文字以下でなければなりません")
}

// カテゴリコード
final case class CategoryCode(value: String) extends AnyVal

// 単位
final case class UnitOfMeasure(value: String) extends AnyVal {
  require(Set("個", "kg", "L", "箱", "ケース").contains(value), s"不正な単位です: $value")
}

// リードタイム
final case class LeadTime(days: Int) extends AnyVal {
  require(days >= 0, "リードタイムは0以上でなければなりません")
}

// 数量
final case class Quantity(value: BigDecimal) extends AnyVal {
  require(value > 0, "数量は0より大きい値でなければなりません")
}

// 保管条件
sealed trait StorageCondition
object StorageCondition {
  case object RoomTemperature extends StorageCondition  // 常温
  case object Refrigerated extends StorageCondition     // 冷蔵
  case object Frozen extends StorageCondition           // 冷凍

  def fromString(s: String): Option[StorageCondition] = s match {
    case "常温" => Some(RoomTemperature)
    case "冷蔵" => Some(Refrigerated)
    case "冷凍" => Some(Frozen)
    case _ => None
  }

  def toString(sc: StorageCondition): String = sc match {
    case RoomTemperature => "常温"
    case Refrigerated => "冷蔵"
    case Frozen => "冷凍"
  }
}

// 商品ステータス
sealed trait ProductStatus
object ProductStatus {
  case object Active extends ProductStatus      // 有効
  case object Suspended extends ProductStatus   // 停止
  case object Obsolete extends ProductStatus    // 廃止

  def fromString(s: String): Option[ProductStatus] = s match {
    case "Active" => Some(Active)
    case "Suspended" => Some(Suspended)
    case "Obsolete" => Some(Obsolete)
    case _ => None
  }
}

// 仕入先ID（第5部で定義されたものを参照）
final case class SupplierId(value: String) extends AnyVal

// 顧客ID（第4部で定義されたものを参照）
final case class CustomerId(value: String) extends AnyVal
```

### 4.1.3 ProductPrice 値オブジェクト

```scala
// 商品価格
final case class ProductPrice(
  priceType: PriceType,
  customerId: Option[CustomerId],
  unitPrice: Money,
  validPeriod: ValidPeriod
) {
  require(unitPrice.amount > 0, "価格は0より大きい値でなければなりません")
  require(
    (priceType == PriceType.Special && customerId.isDefined) ||
    (priceType != PriceType.Special && customerId.isEmpty),
    "特別価格の場合は顧客IDが必要です"
  )

  // 指定日時点で有効か
  def isValidAt(date: LocalDate): Boolean = validPeriod.isValidAt(date)
}

// 価格タイプ
sealed trait PriceType
object PriceType {
  case object Standard extends PriceType  // 標準価格
  case object Special extends PriceType   // 特別価格
  case object Discount extends PriceType  // 割引価格

  def fromString(s: String): Option[PriceType] = s match {
    case "Standard" => Some(Standard)
    case "Special" => Some(Special)
    case "Discount" => Some(Discount)
    case _ => None
  }
}
```

### 4.1.4 ValidPeriod 値オブジェクト

```scala
// 有効期間
final case class ValidPeriod(
  validFrom: LocalDate,
  validTo: Option[LocalDate]
) {
  require(
    validTo.forall(to => !to.isBefore(validFrom)),
    "有効終了日は有効開始日以降でなければなりません"
  )

  // 指定日時点で有効か
  def isValidAt(date: LocalDate): Boolean = {
    !date.isBefore(validFrom) &&
    validTo.forall(to => !date.isAfter(to))
  }

  // 他の有効期間と重複するか
  def overlaps(other: ValidPeriod): Boolean = {
    !isBefore(other) && !isAfter(other)
  }

  private def isBefore(other: ValidPeriod): Boolean = {
    validTo.exists(to => to.isBefore(other.validFrom))
  }

  private def isAfter(other: ValidPeriod): Boolean = {
    other.validTo.exists(to => validFrom.isAfter(to))
  }

  // 期間を終了する
  def close(endDate: LocalDate): ValidPeriod = {
    require(!endDate.isBefore(validFrom), "終了日は開始日以降でなければなりません")
    copy(validTo = Some(endDate))
  }
}

object ValidPeriod {
  // 無期限の有効期間を生成
  def indefinite(from: LocalDate): ValidPeriod = ValidPeriod(from, None)

  // 期間指定の有効期間を生成
  def limited(from: LocalDate, to: LocalDate): ValidPeriod = ValidPeriod(from, Some(to))
}
```

### 4.1.5 Money 値オブジェクト

```scala
// 金額（第4部で定義されたものを再利用）
final case class Money(amount: BigDecimal, currency: Currency = Currency.JPY) {
  require(amount >= 0, "金額は0以上でなければなりません")

  def +(other: Money): Money = {
    require(currency == other.currency, "通貨が一致しません")
    Money(amount + other.amount, currency)
  }

  def -(other: Money): Money = {
    require(currency == other.currency, "通貨が一致しません")
    require(amount >= other.amount, "減算結果が負になります")
    Money(amount - other.amount, currency)
  }

  def *(multiplier: BigDecimal): Money = {
    Money(amount * multiplier, currency)
  }

  def /(divisor: BigDecimal): Money = {
    require(divisor != 0, "0で除算できません")
    Money(amount / divisor, currency)
  }

  def round(scale: Int = 0): Money = {
    Money(amount.setScale(scale, BigDecimal.RoundingMode.HALF_UP), currency)
  }
}

object Money {
  def apply(amount: BigDecimal): Money = Money(amount, Currency.JPY)
  def zero: Money = Money(0)
}

// 通貨
sealed trait Currency
object Currency {
  case object JPY extends Currency  // 日本円
  case object USD extends Currency  // 米ドル
  case object EUR extends Currency  // ユーロ
}
```

## 4.2 AccountSubject集約（勘定科目）

勘定科目は会計システムで使用される科目体系を管理する集約です。階層構造を持ち、財務諸表の表示に使用されます。

### 4.2.1 AccountSubject エンティティ

```scala
package com.example.shareddata.domain.model.accountsubject

import java.time.LocalDate

// 勘定科目エンティティ
final case class AccountSubject(
  id: AccountSubjectId,
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
  status: AccountSubjectStatus,
  validPeriod: ValidPeriod,
  version: Version
) {

  // 階層レベルの検証
  require(level >= 1 && level <= 4, "階層レベルは1から4の範囲でなければなりません")
  require(
    (level == 1 && parentAccountId.isEmpty) || (level > 1 && parentAccountId.isDefined),
    "レベル1の科目は親を持たず、レベル2以降の科目は親を持つ必要があります"
  )

  // 勘定科目名を変更
  def updateName(newName: AccountName): AccountSubject = {
    copy(accountName = newName)
  }

  // 親勘定科目を変更
  def changeParent(newParentId: AccountSubjectId, newLevel: Int): Either[DomainError, AccountSubject] = {
    if (newLevel <= level) {
      Left(DomainError("新しい階層レベルは現在のレベルより大きくなければなりません"))
    } else {
      Right(copy(
        parentAccountId = Some(newParentId),
        level = newLevel
      ))
    }
  }

  // 勘定科目を廃止
  def obsolete(): Either[DomainError, AccountSubject] = {
    status match {
      case AccountSubjectStatus.Obsolete =>
        Left(DomainError("既に廃止済みの勘定科目です"))
      case _ =>
        Right(copy(
          status = AccountSubjectStatus.Obsolete,
          validPeriod = validPeriod.copy(validTo = Some(LocalDate.now()))
        ))
    }
  }

  // 指定日時点で有効か
  def isValidAt(date: LocalDate): Boolean = {
    status == AccountSubjectStatus.Active && validPeriod.isValidAt(date)
  }

  // 補助科目が必要か
  def needsAuxiliaryAccount: Boolean = requiresAuxiliary

  // 貸借対照表に表示するか
  def showInBalanceSheet: Boolean = accountType match {
    case AccountType.Asset | AccountType.Liability | AccountType.Equity => true
    case _ => false
  }

  // 損益計算書に表示するか
  def showInIncomeStatement: Boolean = accountType match {
    case AccountType.Revenue | AccountType.Expense => true
    case _ => false
  }

  // 勘定科目の検証
  def validate(): Either[ValidationError, AccountSubject] = {
    for {
      _ <- validateLevel()
      _ <- validateAuxiliary()
      _ <- validateBalanceSide()
    } yield this
  }

  private def validateLevel(): Either[ValidationError, Unit] = {
    if (level < 1 || level > 4) {
      Left(ValidationError("階層レベルは1から4の範囲でなければなりません"))
    } else {
      Right(())
    }
  }

  private def validateAuxiliary(): Either[ValidationError, Unit] = {
    if (requiresAuxiliary && auxiliaryType.isEmpty) {
      Left(ValidationError("補助科目必須の場合は補助科目タイプを指定してください"))
    } else {
      Right(())
    }
  }

  private def validateBalanceSide(): Either[ValidationError, Unit] = {
    val expectedSide = accountType match {
      case AccountType.Asset | AccountType.Expense => BalanceSide.Debit
      case AccountType.Liability | AccountType.Equity | AccountType.Revenue => BalanceSide.Credit
    }

    if (balanceSide != expectedSide) {
      Left(ValidationError(s"勘定科目タイプ${accountType}の貸借区分は${expectedSide}でなければなりません"))
    } else {
      Right(())
    }
  }
}
```

### 4.2.2 値オブジェクト

```scala
// 勘定科目ID
final case class AccountSubjectId(value: String) extends AnyVal {
  override def toString: String = value
}

object AccountSubjectId {
  def generate(): AccountSubjectId = AccountSubjectId(UUID.randomUUID().toString)
}

// 勘定科目コード
final case class AccountCode(value: String) extends AnyVal {
  require(value.matches("^\\d{4}$"), "勘定科目コードは4桁の数字でなければなりません")
}

// 勘定科目名
final case class AccountName(value: String) extends AnyVal {
  require(value.nonEmpty && value.length <= 100, "勘定科目名は1文字以上100文字以下でなければなりません")
}

// 勘定科目タイプ
sealed trait AccountType
object AccountType {
  case object Asset extends AccountType       // 資産
  case object Liability extends AccountType   // 負債
  case object Equity extends AccountType      // 純資産
  case object Revenue extends AccountType     // 収益
  case object Expense extends AccountType     // 費用

  def fromString(s: String): Option[AccountType] = s match {
    case "Asset" => Some(Asset)
    case "Liability" => Some(Liability)
    case "Equity" => Some(Equity)
    case "Revenue" => Some(Revenue)
    case "Expense" => Some(Expense)
    case _ => None
  }

  def toJapanese(at: AccountType): String = at match {
    case Asset => "資産"
    case Liability => "負債"
    case Equity => "純資産"
    case Revenue => "収益"
    case Expense => "費用"
  }
}

// 勘定科目サブタイプ
sealed trait AccountSubtype
object AccountSubtype {
  // 資産
  case object CurrentAsset extends AccountSubtype    // 流動資産
  case object FixedAsset extends AccountSubtype      // 固定資産

  // 負債
  case object CurrentLiability extends AccountSubtype // 流動負債
  case object FixedLiability extends AccountSubtype   // 固定負債

  // 収益・費用
  case object OperatingRevenue extends AccountSubtype      // 営業収益
  case object NonOperatingRevenue extends AccountSubtype   // 営業外収益
  case object OperatingExpense extends AccountSubtype      // 営業費用
  case object NonOperatingExpense extends AccountSubtype   // 営業外費用

  def fromString(s: String): Option[AccountSubtype] = s match {
    case "Current" => Some(CurrentAsset) // 汎用的な「流動」
    case "Fixed" => Some(FixedAsset)     // 汎用的な「固定」
    case "Operating" => Some(OperatingRevenue)
    case "NonOperating" => Some(NonOperatingRevenue)
    case _ => None
  }
}

// 貸借区分
sealed trait BalanceSide
object BalanceSide {
  case object Debit extends BalanceSide   // 借方
  case object Credit extends BalanceSide  // 貸方

  def fromString(s: String): Option[BalanceSide] = s match {
    case "Debit" => Some(Debit)
    case "Credit" => Some(Credit)
    case _ => None
  }

  def toJapanese(bs: BalanceSide): String = bs match {
    case Debit => "借方"
    case Credit => "貸方"
  }
}

// 補助科目タイプ
sealed trait AuxiliaryType
object AuxiliaryType {
  case object Customer extends AuxiliaryType   // 顧客
  case object Supplier extends AuxiliaryType   // 仕入先
  case object Department extends AuxiliaryType // 部門
  case object Employee extends AuxiliaryType   // 社員

  def fromString(s: String): Option[AuxiliaryType] = s match {
    case "Customer" => Some(Customer)
    case "Supplier" => Some(Supplier)
    case "Department" => Some(Department)
    case "Employee" => Some(Employee)
    case _ => None
  }
}

// 勘定科目ステータス
sealed trait AccountSubjectStatus
object AccountSubjectStatus {
  case object Active extends AccountSubjectStatus    // 有効
  case object Obsolete extends AccountSubjectStatus  // 廃止

  def fromString(s: String): Option[AccountSubjectStatus] = s match {
    case "Active" => Some(Active)
    case "Obsolete" => Some(Obsolete)
    case _ => None
  }
}
```

## 4.3 CodeMaster集約（コードマスター）

コードマスターは税率、支払条件、配送方法などの共通コードを管理する集約です。

### 4.3.1 CodeMaster エンティティ

```scala
package com.example.shareddata.domain.model.codemaster

import java.time.LocalDate

// コードマスターエンティティ
final case class CodeMaster(
  id: CodeMasterId,
  codeType: CodeType,
  codeValue: CodeValue,
  displayName: DisplayName,
  displayNameShort: Option[String],
  description: Option[String],
  displayOrder: Int,
  additionalData: Option[AdditionalData],
  status: CodeMasterStatus,
  validPeriod: ValidPeriod,
  version: Version
) {

  // 表示名を更新
  def updateDisplayName(newName: DisplayName, newShortName: Option[String]): CodeMaster = {
    copy(
      displayName = newName,
      displayNameShort = newShortName
    )
  }

  // 説明を更新
  def updateDescription(newDescription: String): CodeMaster = {
    copy(description = Some(newDescription))
  }

  // 追加データを更新
  def updateAdditionalData(newData: AdditionalData): CodeMaster = {
    copy(additionalData = Some(newData))
  }

  // 表示順序を変更
  def changeDisplayOrder(newOrder: Int): Either[DomainError, CodeMaster] = {
    if (newOrder < 1) {
      Left(DomainError("表示順序は1以上でなければなりません"))
    } else {
      Right(copy(displayOrder = newOrder))
    }
  }

  // コードマスターを無効化
  def deactivate(): Either[DomainError, CodeMaster] = {
    status match {
      case CodeMasterStatus.Inactive =>
        Left(DomainError("既に無効化されています"))
      case _ =>
        Right(copy(
          status = CodeMasterStatus.Inactive,
          validPeriod = validPeriod.copy(validTo = Some(LocalDate.now()))
        ))
    }
  }

  // 指定日時点で有効か
  def isValidAt(date: LocalDate): Boolean = {
    status == CodeMasterStatus.Active && validPeriod.isValidAt(date)
  }

  // 税率を取得（税率タイプの場合）
  def taxRate: Option[BigDecimal] = {
    if (codeType.value == "TaxRate") {
      additionalData.flatMap(_.getField("rate").map(BigDecimal(_)))
    } else {
      None
    }
  }

  // 配送料を取得（配送方法タイプの場合）
  def shippingFee: Option[BigDecimal] = {
    if (codeType.value == "ShippingMethod") {
      additionalData.flatMap(_.getField("fee").map(BigDecimal(_)))
    } else {
      None
    }
  }

  // コードマスターの検証
  def validate(): Either[ValidationError, CodeMaster] = {
    for {
      _ <- validateDisplayOrder()
      _ <- validateAdditionalData()
    } yield this
  }

  private def validateDisplayOrder(): Either[ValidationError, Unit] = {
    if (displayOrder < 1) {
      Left(ValidationError("表示順序は1以上でなければなりません"))
    } else {
      Right(())
    }
  }

  private def validateAdditionalData(): Either[ValidationError, Unit] = {
    // 税率タイプの場合はrateフィールドが必須
    if (codeType.value == "TaxRate") {
      additionalData match {
        case Some(data) if data.hasField("rate") => Right(())
        case _ => Left(ValidationError("税率タイプの場合はrateフィールドが必須です"))
      }
    } else {
      Right(())
    }
  }
}
```

### 4.3.2 値オブジェクト

```scala
// コードマスターID
final case class CodeMasterId(value: String) extends AnyVal {
  override def toString: String = value
}

object CodeMasterId {
  def generate(): CodeMasterId = CodeMasterId(UUID.randomUUID().toString)
}

// コードタイプ
final case class CodeType(value: String) extends AnyVal {
  require(
    Set("TaxRate", "PaymentTerms", "ShippingMethod", "UnitOfMeasure", "ProductCategory").contains(value),
    s"不正なコードタイプです: $value"
  )
}

object CodeType {
  val TaxRate = CodeType("TaxRate")
  val PaymentTerms = CodeType("PaymentTerms")
  val ShippingMethod = CodeType("ShippingMethod")
  val UnitOfMeasure = CodeType("UnitOfMeasure")
  val ProductCategory = CodeType("ProductCategory")
}

// コード値
final case class CodeValue(value: String) extends AnyVal {
  require(value.nonEmpty && value.length <= 50, "コード値は1文字以上50文字以下でなければなりません")
}

// 表示名
final case class DisplayName(value: String) extends AnyVal {
  require(value.nonEmpty && value.length <= 200, "表示名は1文字以上200文字以下でなければなりません")
}

// 追加データ（JSON形式）
final case class AdditionalData(fields: Map[String, String]) {
  def getField(key: String): Option[String] = fields.get(key)
  def hasField(key: String): Boolean = fields.contains(key)
  def addField(key: String, value: String): AdditionalData = {
    AdditionalData(fields + (key -> value))
  }
  def removeField(key: String): AdditionalData = {
    AdditionalData(fields - key)
  }

  // JSON文字列に変換
  def toJson: String = {
    import io.circe.syntax._
    import io.circe.generic.auto._
    fields.asJson.noSpaces
  }
}

object AdditionalData {
  def empty: AdditionalData = AdditionalData(Map.empty)

  // JSON文字列から復元
  def fromJson(json: String): Either[io.circe.Error, AdditionalData] = {
    import io.circe.parser._
    import io.circe.generic.auto._
    decode[Map[String, String]](json).map(AdditionalData(_))
  }
}

// コードマスターステータス
sealed trait CodeMasterStatus
object CodeMasterStatus {
  case object Active extends CodeMasterStatus    // 有効
  case object Inactive extends CodeMasterStatus  // 無効

  def fromString(s: String): Option[CodeMasterStatus] = s match {
    case "Active" => Some(Active)
    case "Inactive" => Some(Inactive)
    case _ => None
  }
}
```

## 4.4 共通の値オブジェクトとエラー型

### 4.4.1 Version 値オブジェクト

```scala
// バージョン（楽観的ロック用）
final case class Version(value: Long) extends AnyVal {
  def increment: Version = Version(value + 1)
}

object Version {
  val initial: Version = Version(1)
}
```

### 4.4.2 エラー型

```scala
// ドメインエラー
final case class DomainError(message: String) extends Exception(message)

// バリデーションエラー
final case class ValidationError(message: String) extends Exception(message)

// 競合エラー
final case class ConflictError(message: String) extends Exception(message)

// Not Foundエラー
final case class NotFoundError(entityType: String, id: String)
  extends Exception(s"$entityType not found: $id")
```

## 4.5 まとめ

本章では、共用データ管理サービスの3つの集約を設計しました。

**Product集約**:
- 商品の基本情報、価格、調達情報を管理
- 価格履歴の管理（有効期間による時点管理）
- 顧客別特別価格の管理
- 商品ライフサイクル（有効、停止、廃止）の管理

**AccountSubject集約**:
- 勘定科目体系の階層構造管理
- 貸借区分と科目タイプの整合性検証
- 補助科目の設定
- 財務諸表への表示制御

**CodeMaster集約**:
- 各種共通コードの一元管理
- JSON形式の追加データによる柔軟な拡張
- コードタイプごとの固有データ（税率、配送料など）

**共通の設計原則**:
- 不変性（Immutable）: 全てのドメインオブジェクトは不変
- 値オブジェクト: プリミティブ型のラップによる型安全性
- 有効期間管理: ValidPeriodによる時点データの管理
- バリデーション: ビジネスルールの明示的な検証
- エラーハンドリング: Either型による明示的なエラー処理

次章では、これらの集約を実装します。
