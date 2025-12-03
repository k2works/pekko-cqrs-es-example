# 第7部第9章：GraphQL APIの実装

## 本章の目的

本章では、共用データ管理サービスのGraphQL APIを詳細に実装します。Sangria GraphQLを使用して、商品マスター、勘定科目マスター、コードマスター、変更申請を管理するための柔軟で効率的なAPIを構築します。

## 9.1 GraphQLスキーマの全体設計

### 9.1.1 スキーマ設計の原則

GraphQLスキーマ設計では、以下の原則に従います：

1. **クライアント中心の設計**: クライアントが必要とするデータのみを取得可能に
2. **型安全性**: 強力な型システムによる安全性の確保
3. **進化可能性**: スキーマの後方互換性を保ちながら進化可能
4. **パフォーマンス**: DataLoaderによるN+1問題の解決
5. **ページング**: Relay仕様準拠のカーソルベースページング

### 9.1.2 共通型定義

すべてのAPIで使用する共通型を定義します。

```scala
package com.example.masterdata.query.graphql

import sangria.schema._
import sangria.macros.derive._

/**
 * 共通GraphQL型定義
 */
object CommonTypes {

  // ページ情報型（Relay仕様準拠）
  val PageInfoType = ObjectType(
    "PageInfo",
    "ページネーション情報",
    fields[Unit, PageInfo](
      Field("hasNextPage", BooleanType,
        Some("次のページが存在するか"),
        resolve = _.value.hasNextPage),
      Field("hasPreviousPage", BooleanType,
        Some("前のページが存在するか"),
        resolve = _.value.hasPreviousPage),
      Field("startCursor", OptionType(StringType),
        Some("最初のエッジのカーソル"),
        resolve = _.value.startCursor),
      Field("endCursor", OptionType(StringType),
        Some("最後のエッジのカーソル"),
        resolve = _.value.endCursor)
    )
  )

  // カスタムスカラー型: Date
  val DateType = ScalarType[LocalDate](
    "Date",
    description = Some("日付型（YYYY-MM-DD形式）"),
    coerceOutput = (date, _) => date.toString,
    coerceUserInput = {
      case s: String => Right(LocalDate.parse(s))
      case _ => Left(DateCoercionViolation)
    },
    coerceInput = {
      case sangria.ast.StringValue(s, _, _, _, _) =>
        Try(LocalDate.parse(s)) match {
          case Success(date) => Right(date)
          case Failure(_) => Left(DateCoercionViolation)
        }
      case _ => Left(DateCoercionViolation)
    }
  )

  // カスタムスカラー型: DateTime
  val DateTimeType = ScalarType[Instant](
    "DateTime",
    description = Some("日時型（ISO 8601形式）"),
    coerceOutput = (instant, _) => instant.toString,
    coerceUserInput = {
      case s: String => Right(Instant.parse(s))
      case _ => Left(DateTimeCoercionViolation)
    },
    coerceInput = {
      case sangria.ast.StringValue(s, _, _, _, _) =>
        Try(Instant.parse(s)) match {
          case Success(instant) => Right(instant)
          case Failure(_) => Left(DateTimeCoercionViolation)
        }
      case _ => Left(DateTimeCoercionViolation)
    }
  )

  // カスタムスカラー型: Decimal
  val DecimalType = ScalarType[BigDecimal](
    "Decimal",
    description = Some("10進数型（高精度計算用）"),
    coerceOutput = (value, _) => value.toString,
    coerceUserInput = {
      case s: String => Right(BigDecimal(s))
      case i: Int => Right(BigDecimal(i))
      case l: Long => Right(BigDecimal(l))
      case d: Double => Right(BigDecimal(d))
      case _ => Left(DecimalCoercionViolation)
    },
    coerceInput = {
      case sangria.ast.StringValue(s, _, _, _, _) =>
        Try(BigDecimal(s)) match {
          case Success(decimal) => Right(decimal)
          case Failure(_) => Left(DecimalCoercionViolation)
        }
      case sangria.ast.IntValue(i, _, _) => Right(BigDecimal(i))
      case sangria.ast.BigIntValue(i, _, _) => Right(BigDecimal(i))
      case sangria.ast.FloatValue(f, _, _) => Right(BigDecimal(f))
      case sangria.ast.BigDecimalValue(d, _, _) => Right(d)
      case _ => Left(DecimalCoercionViolation)
    }
  )

  case object DateCoercionViolation extends ValueCoercionViolation("Date値が期待されます（YYYY-MM-DD形式）")
  case object DateTimeCoercionViolation extends ValueCoercionViolation("DateTime値が期待されます（ISO 8601形式）")
  case object DecimalCoercionViolation extends ValueCoercionViolation("Decimal値が期待されます")
}

// ページ情報モデル
case class PageInfo(
  hasNextPage: Boolean,
  hasPreviousPage: Boolean,
  startCursor: Option[String],
  endCursor: Option[String]
)
```

## 9.2 商品マスターAPI

### 9.2.1 商品スキーマ定義

```scala
package com.example.masterdata.query.graphql

import sangria.schema._
import CommonTypes._

object ProductSchema {

  // 商品ステータス列挙型
  val ProductStatusType = EnumType(
    "ProductStatus",
    description = Some("商品ステータス"),
    values = List(
      EnumValue("ACTIVE",
        value = "Active",
        description = Some("有効")),
      EnumValue("SUSPENDED",
        value = "Suspended",
        description = Some("停止中")),
      EnumValue("OBSOLETE",
        value = "Obsolete",
        description = Some("廃止"))
    )
  )

  // 価格タイプ列挙型
  val PriceTypeType = EnumType(
    "PriceType",
    description = Some("価格タイプ"),
    values = List(
      EnumValue("STANDARD",
        value = "Standard",
        description = Some("標準価格")),
      EnumValue("SPECIAL",
        value = "Special",
        description = Some("特別価格")),
      EnumValue("DISCOUNT",
        value = "Discount",
        description = Some("割引価格"))
    )
  )

  // 商品価格型
  val ProductPriceType = ObjectType(
    "ProductPrice",
    "商品価格情報",
    fields[MasterDataContext, ProductPriceView](
      Field("id", IDType,
        Some("価格ID"),
        resolve = _.value.id),
      Field("priceType", PriceTypeType,
        Some("価格タイプ"),
        resolve = _.value.priceType),
      Field("unitPrice", DecimalType,
        Some("単価"),
        resolve = _.value.unitPrice),
      Field("customerId", OptionType(IDType),
        Some("顧客ID（特別価格の場合）"),
        resolve = _.value.customerId),
      Field("validFrom", DateType,
        Some("有効開始日"),
        resolve = _.value.validFrom),
      Field("validTo", OptionType(DateType),
        Some("有効終了日"),
        resolve = _.value.validTo)
    )
  )

  // 商品型
  val ProductType = ObjectType(
    "Product",
    "商品マスター",
    fields[MasterDataContext, ProductView](
      Field("id", IDType,
        Some("商品ID"),
        resolve = _.value.id),
      Field("productCode", StringType,
        Some("商品コード"),
        resolve = _.value.productCode),
      Field("productName", StringType,
        Some("商品名"),
        resolve = _.value.productName),
      Field("categoryCode", StringType,
        Some("カテゴリーコード"),
        resolve = _.value.categoryCode),
      Field("unitOfMeasure", StringType,
        Some("単位"),
        resolve = _.value.unitOfMeasure),
      Field("standardCost", DecimalType,
        Some("標準原価"),
        resolve = _.value.standardCost),
      Field("listPrice", DecimalType,
        Some("定価"),
        resolve = _.value.listPrice),
      Field("primarySupplierId", OptionType(IDType),
        Some("主要仕入先ID"),
        resolve = _.value.primarySupplierId),
      Field("leadTimeDays", IntType,
        Some("リードタイム（日数）"),
        resolve = _.value.leadTimeDays),
      Field("minimumOrderQuantity", DecimalType,
        Some("最小発注数量"),
        resolve = _.value.minimumOrderQuantity),
      Field("status", ProductStatusType,
        Some("ステータス"),
        resolve = _.value.status),
      Field("validFrom", DateType,
        Some("有効開始日"),
        resolve = _.value.validFrom),
      Field("validTo", OptionType(DateType),
        Some("有効終了日"),
        resolve = _.value.validTo),
      Field("prices", ListType(ProductPriceType),
        Some("価格一覧"),
        resolve = ctx => ctx.ctx.productPriceLoader.load(ctx.value.id)),
      Field("version", IntType,
        Some("バージョン"),
        resolve = _.value.version),
      Field("createdAt", DateTimeType,
        Some("作成日時"),
        resolve = _.value.createdAt),
      Field("updatedAt", DateTimeType,
        Some("更新日時"),
        resolve = _.value.updatedAt)
    )
  )

  // 商品エッジ型
  val ProductEdgeType = ObjectType(
    "ProductEdge",
    "商品エッジ（ページング用）",
    fields[MasterDataContext, ProductEdge](
      Field("node", ProductType,
        Some("商品ノード"),
        resolve = _.value.node),
      Field("cursor", StringType,
        Some("カーソル"),
        resolve = _.value.cursor)
    )
  )

  // 商品コネクション型
  val ProductConnectionType = ObjectType(
    "ProductConnection",
    "商品コネクション（ページング用）",
    fields[MasterDataContext, ProductConnection](
      Field("edges", ListType(ProductEdgeType),
        Some("エッジ一覧"),
        resolve = _.value.edges),
      Field("pageInfo", PageInfoType,
        Some("ページ情報"),
        resolve = _.value.pageInfo),
      Field("totalCount", IntType,
        Some("総件数"),
        resolve = _.value.totalCount)
    )
  )

  // 商品フィルター入力型
  val ProductFilterInputType = InputObjectType[ProductFilter](
    "ProductFilter",
    "商品フィルター条件",
    List(
      InputField("productCode", OptionInputType(StringType),
        description = "商品コード（部分一致）"),
      InputField("productName", OptionInputType(StringType),
        description = "商品名（部分一致）"),
      InputField("categoryCode", OptionInputType(StringType),
        description = "カテゴリーコード（完全一致）"),
      InputField("status", OptionInputType(ProductStatusType),
        description = "ステータス"),
      InputField("validAt", OptionInputType(DateType),
        description = "指定日時点で有効な商品のみ")
    )
  )

  // 商品作成入力型
  val CreateProductInputType = InputObjectType[CreateProductInput](
    "CreateProductInput",
    "商品作成入力",
    List(
      InputField("productCode", StringType,
        description = "商品コード"),
      InputField("productName", StringType,
        description = "商品名"),
      InputField("categoryCode", StringType,
        description = "カテゴリーコード"),
      InputField("unitOfMeasure", StringType,
        description = "単位"),
      InputField("standardCost", DecimalType,
        description = "標準原価"),
      InputField("listPrice", DecimalType,
        description = "定価"),
      InputField("primarySupplierId", OptionInputType(IDType),
        description = "主要仕入先ID"),
      InputField("leadTimeDays", IntType,
        description = "リードタイム（日数）"),
      InputField("minimumOrderQuantity", DecimalType,
        description = "最小発注数量")
    )
  )

  // 商品情報更新入力型
  val UpdateProductInfoInputType = InputObjectType[UpdateProductInfoInput](
    "UpdateProductInfoInput",
    "商品情報更新入力",
    List(
      InputField("productId", IDType,
        description = "商品ID"),
      InputField("productName", StringType,
        description = "商品名"),
      InputField("categoryCode", StringType,
        description = "カテゴリーコード"),
      InputField("unitOfMeasure", StringType,
        description = "単位"),
      InputField("leadTimeDays", IntType,
        description = "リードタイム（日数）"),
      InputField("minimumOrderQuantity", DecimalType,
        description = "最小発注数量")
    )
  )

  // 価格変更入力型
  val ChangeProductPriceInputType = InputObjectType[ChangeProductPriceInput](
    "ChangeProductPriceInput",
    "価格変更入力",
    List(
      InputField("productId", IDType,
        description = "商品ID"),
      InputField("newPrice", DecimalType,
        description = "新価格"),
      InputField("effectiveFrom", DateType,
        description = "有効開始日"),
      InputField("reason", StringType,
        description = "変更理由")
    )
  )

  // Payload型
  val CreateProductPayloadType = ObjectType(
    "CreateProductPayload",
    "商品作成結果",
    fields[MasterDataContext, CreateProductPayload](
      Field("product", ProductType,
        Some("作成された商品"),
        resolve = _.value.product),
      Field("success", BooleanType,
        Some("成功したか"),
        resolve = _.value.success),
      Field("message", OptionType(StringType),
        Some("メッセージ"),
        resolve = _.value.message)
    )
  )

  val UpdateProductInfoPayloadType = ObjectType(
    "UpdateProductInfoPayload",
    "商品情報更新結果",
    fields[MasterDataContext, UpdateProductInfoPayload](
      Field("product", ProductType,
        Some("更新された商品"),
        resolve = _.value.product),
      Field("success", BooleanType,
        Some("成功したか"),
        resolve = _.value.success),
      Field("message", OptionType(StringType),
        Some("メッセージ"),
        resolve = _.value.message)
    )
  )

  val ChangeProductPricePayloadType = ObjectType(
    "ChangeProductPricePayload",
    "価格変更結果",
    fields[MasterDataContext, ChangeProductPricePayload](
      Field("product", OptionType(ProductType),
        Some("変更された商品（即座に適用された場合）"),
        resolve = _.value.product),
      Field("changeRequest", OptionType(ChangeRequestType),
        Some("変更申請（承認が必要な場合）"),
        resolve = _.value.changeRequest),
      Field("requiresApproval", BooleanType,
        Some("承認が必要か"),
        resolve = _.value.requiresApproval),
      Field("success", BooleanType,
        Some("成功したか"),
        resolve = _.value.success),
      Field("message", OptionType(StringType),
        Some("メッセージ"),
        resolve = _.value.message)
    )
  )
}

// ビューモデル
case class ProductView(
  id: String,
  productCode: String,
  productName: String,
  categoryCode: String,
  unitOfMeasure: String,
  standardCost: BigDecimal,
  listPrice: BigDecimal,
  primarySupplierId: Option[String],
  leadTimeDays: Int,
  minimumOrderQuantity: BigDecimal,
  status: String,
  validFrom: LocalDate,
  validTo: Option[LocalDate],
  version: Int,
  createdAt: Instant,
  updatedAt: Instant
)

case class ProductPriceView(
  id: String,
  priceType: String,
  unitPrice: BigDecimal,
  customerId: Option[String],
  validFrom: LocalDate,
  validTo: Option[LocalDate]
)

case class ProductEdge(node: ProductView, cursor: String)
case class ProductConnection(edges: List[ProductEdge], pageInfo: PageInfo, totalCount: Int)

case class ProductFilter(
  productCode: Option[String],
  productName: Option[String],
  categoryCode: Option[String],
  status: Option[String],
  validAt: Option[LocalDate]
)

case class CreateProductInput(
  productCode: String,
  productName: String,
  categoryCode: String,
  unitOfMeasure: String,
  standardCost: BigDecimal,
  listPrice: BigDecimal,
  primarySupplierId: Option[String],
  leadTimeDays: Int,
  minimumOrderQuantity: BigDecimal
)

case class UpdateProductInfoInput(
  productId: String,
  productName: String,
  categoryCode: String,
  unitOfMeasure: String,
  leadTimeDays: Int,
  minimumOrderQuantity: BigDecimal
)

case class ChangeProductPriceInput(
  productId: String,
  newPrice: BigDecimal,
  effectiveFrom: LocalDate,
  reason: String
)

case class CreateProductPayload(product: ProductView, success: Boolean, message: Option[String])
case class UpdateProductInfoPayload(product: ProductView, success: Boolean, message: Option[String])
case class ChangeProductPricePayload(
  product: Option[ProductView],
  changeRequest: Option[ChangeRequestView],
  requiresApproval: Boolean,
  success: Boolean,
  message: Option[String]
)
```

### 9.2.2 商品クエリ定義

```scala
object ProductQueries {

  val queries = fields[MasterDataContext, Unit](
    Field(
      "products",
      ProductConnectionType,
      description = Some("商品一覧を取得（ページング対応）"),
      arguments = List(
        Argument("filter", OptionInputType(ProductFilterInputType),
          description = "フィルター条件"),
        Argument("first", OptionInputType(IntType),
          description = "取得件数"),
        Argument("after", OptionInputType(StringType),
          description = "開始カーソル"),
        Argument("page", OptionInputType(IntType), defaultValue = 1,
          description = "ページ番号"),
        Argument("pageSize", OptionInputType(IntType), defaultValue = 20,
          description = "ページサイズ")
      ),
      resolve = ctx => {
        val filter = ctx.argOpt[ProductFilter]("filter")
        val first = ctx.argOpt[Int]("first")
        val after = ctx.argOpt[String]("after")
        val page = ctx.arg[Int]("page")
        val pageSize = ctx.arg[Int]("pageSize")

        ctx.ctx.productRepository.findWithPagination(filter, page, pageSize)
      }
    ),
    Field(
      "product",
      OptionType(ProductType),
      description = Some("商品IDで商品を取得"),
      arguments = List(Argument("id", IDType, description = "商品ID")),
      resolve = ctx => {
        val id = ctx.arg[String]("id")
        ctx.ctx.productRepository.findById(UUID.fromString(id))
      }
    ),
    Field(
      "productByCode",
      OptionType(ProductType),
      description = Some("商品コードで商品を取得"),
      arguments = List(Argument("productCode", StringType, description = "商品コード")),
      resolve = ctx => {
        val productCode = ctx.arg[String]("productCode")
        ctx.ctx.productRepository.findByProductCode(productCode)
      }
    ),
    Field(
      "activeProductsAt",
      ListType(ProductType),
      description = Some("指定日時点で有効な商品一覧を取得"),
      arguments = List(Argument("date", DateType, description = "基準日")),
      resolve = ctx => {
        val date = ctx.arg[LocalDate]("date")
        ctx.ctx.productRepository.findActiveAt(date)
      }
    ),
    Field(
      "productPrice",
      OptionType(ProductPriceType),
      description = Some("商品の指定日時点での価格を取得"),
      arguments = List(
        Argument("productId", IDType, description = "商品ID"),
        Argument("date", DateType, description = "基準日"),
        Argument("customerId", OptionInputType(IDType), description = "顧客ID（特別価格を考慮）")
      ),
      resolve = ctx => {
        val productId = UUID.fromString(ctx.arg[String]("productId"))
        val date = ctx.arg[LocalDate]("date")
        val customerId = ctx.argOpt[String]("customerId")

        ctx.ctx.productPriceRepository.findPriceAt(productId, date, customerId)
      }
    ),
    Field(
      "productsByCategory",
      ListType(ProductType),
      description = Some("カテゴリー別商品一覧を取得"),
      arguments = List(Argument("categoryCode", StringType, description = "カテゴリーコード")),
      resolve = ctx => {
        val categoryCode = ctx.arg[String]("categoryCode")
        ctx.ctx.productRepository.findByCategory(categoryCode)
      }
    ),
    Field(
      "searchProducts",
      ProductConnectionType,
      description = Some("商品を全文検索（商品名、商品コード）"),
      arguments = List(
        Argument("query", StringType, description = "検索クエリ"),
        Argument("page", OptionInputType(IntType), defaultValue = 1, description = "ページ番号"),
        Argument("pageSize", OptionInputType(IntType), defaultValue = 20, description = "ページサイズ")
      ),
      resolve = ctx => {
        val query = ctx.arg[String]("query")
        val page = ctx.arg[Int]("page")
        val pageSize = ctx.arg[Int]("pageSize")

        ctx.ctx.productRepository.search(query, page, pageSize)
      }
    )
  )
}
```

### 9.2.3 商品ミューテーション定義

```scala
object ProductMutations {

  val mutations = fields[MasterDataContext, Unit](
    Field(
      "createProduct",
      CreateProductPayloadType,
      description = Some("商品を作成"),
      arguments = List(Argument("input", CreateProductInputType, description = "商品作成入力")),
      resolve = ctx => {
        val input = ctx.arg[CreateProductInput]("input")
        ctx.ctx.productCommandService.createProduct(input)
      }
    ),
    Field(
      "updateProductInfo",
      UpdateProductInfoPayloadType,
      description = Some("商品情報を更新"),
      arguments = List(Argument("input", UpdateProductInfoInputType, description = "商品情報更新入力")),
      resolve = ctx => {
        val input = ctx.arg[UpdateProductInfoInput]("input")
        ctx.ctx.productCommandService.updateProductInfo(input)
      }
    ),
    Field(
      "changeProductPrice",
      ChangeProductPricePayloadType,
      description = Some("商品価格を変更（承認が必要な場合は申請として登録）"),
      arguments = List(Argument("input", ChangeProductPriceInputType, description = "価格変更入力")),
      resolve = ctx => {
        val input = ctx.arg[ChangeProductPriceInput]("input")
        ctx.ctx.productCommandService.changeProductPrice(input)
      }
    ),
    Field(
      "suspendProduct",
      UpdateProductInfoPayloadType,
      description = Some("商品を停止"),
      arguments = List(Argument("productId", IDType, description = "商品ID")),
      resolve = ctx => {
        val productId = ctx.arg[String]("productId")
        ctx.ctx.productCommandService.suspendProduct(productId)
      }
    ),
    Field(
      "reactivateProduct",
      UpdateProductInfoPayloadType,
      description = Some("商品を再開"),
      arguments = List(Argument("productId", IDType, description = "商品ID")),
      resolve = ctx => {
        val productId = ctx.arg[String]("productId")
        ctx.ctx.productCommandService.reactivateProduct(productId)
      }
    ),
    Field(
      "obsoleteProduct",
      UpdateProductInfoPayloadType,
      description = Some("商品を廃止"),
      arguments = List(Argument("productId", IDType, description = "商品ID")),
      resolve = ctx => {
        val productId = ctx.arg[String]("productId")
        ctx.ctx.productCommandService.obsoleteProduct(productId)
      }
    )
  )
}
```

## 9.3 勘定科目マスターAPI

### 9.3.1 勘定科目スキーマ定義

```scala
object AccountSubjectSchema {

  // 勘定科目種別列挙型
  val AccountTypeType = EnumType(
    "AccountType",
    description = Some("勘定科目種別"),
    values = List(
      EnumValue("ASSET", value = "Asset", description = Some("資産")),
      EnumValue("LIABILITY", value = "Liability", description = Some("負債")),
      EnumValue("EQUITY", value = "Equity", description = Some("純資産")),
      EnumValue("REVENUE", value = "Revenue", description = Some("収益")),
      EnumValue("EXPENSE", value = "Expense", description = Some("費用"))
    )
  )

  // 勘定科目ステータス列挙型
  val AccountSubjectStatusType = EnumType(
    "AccountSubjectStatus",
    description = Some("勘定科目ステータス"),
    values = List(
      EnumValue("ACTIVE", value = "Active", description = Some("有効")),
      EnumValue("SUSPENDED", value = "Suspended", description = Some("停止中")),
      EnumValue("OBSOLETE", value = "Obsolete", description = Some("廃止"))
    )
  )

  // 勘定科目型
  val AccountSubjectType: ObjectType[MasterDataContext, AccountSubjectView] = ObjectType(
    "AccountSubject",
    "勘定科目マスター",
    () => fields[MasterDataContext, AccountSubjectView](
      Field("id", IDType,
        Some("勘定科目ID"),
        resolve = _.value.id),
      Field("accountCode", StringType,
        Some("勘定科目コード"),
        resolve = _.value.accountCode),
      Field("accountName", StringType,
        Some("勘定科目名"),
        resolve = _.value.accountName),
      Field("accountType", AccountTypeType,
        Some("勘定科目種別"),
        resolve = _.value.accountType),
      Field("parentAccountSubjectId", OptionType(IDType),
        Some("親勘定科目ID"),
        resolve = _.value.parentAccountSubjectId),
      Field("parentAccountSubject", OptionType(AccountSubjectType),
        Some("親勘定科目"),
        resolve = ctx => {
          ctx.value.parentAccountSubjectId match {
            case Some(parentId) => ctx.ctx.accountSubjectLoader.load(parentId)
            case None => Future.successful(None)
          }
        }),
      Field("childAccountSubjects", ListType(AccountSubjectType),
        Some("子勘定科目一覧"),
        resolve = ctx => ctx.ctx.accountSubjectRepository.findByParentId(ctx.value.id)),
      Field("level", IntType,
        Some("階層レベル"),
        resolve = _.value.level),
      Field("status", AccountSubjectStatusType,
        Some("ステータス"),
        resolve = _.value.status),
      Field("validFrom", DateType,
        Some("有効開始日"),
        resolve = _.value.validFrom),
      Field("validTo", OptionType(DateType),
        Some("有効終了日"),
        resolve = _.value.validTo),
      Field("version", IntType,
        Some("バージョン"),
        resolve = _.value.version)
    )
  )

  // 勘定科目エッジ型
  val AccountSubjectEdgeType = ObjectType(
    "AccountSubjectEdge",
    "勘定科目エッジ",
    fields[MasterDataContext, AccountSubjectEdge](
      Field("node", AccountSubjectType, resolve = _.value.node),
      Field("cursor", StringType, resolve = _.value.cursor)
    )
  )

  // 勘定科目コネクション型
  val AccountSubjectConnectionType = ObjectType(
    "AccountSubjectConnection",
    "勘定科目コネクション",
    fields[MasterDataContext, AccountSubjectConnection](
      Field("edges", ListType(AccountSubjectEdgeType), resolve = _.value.edges),
      Field("pageInfo", PageInfoType, resolve = _.value.pageInfo),
      Field("totalCount", IntType, resolve = _.value.totalCount)
    )
  )

  // 勘定科目フィルター入力型
  val AccountSubjectFilterInputType = InputObjectType[AccountSubjectFilter](
    "AccountSubjectFilter",
    List(
      InputField("accountCode", OptionInputType(StringType)),
      InputField("accountName", OptionInputType(StringType)),
      InputField("accountType", OptionInputType(AccountTypeType)),
      InputField("status", OptionInputType(AccountSubjectStatusType)),
      InputField("validAt", OptionInputType(DateType))
    )
  )
}

case class AccountSubjectView(
  id: String,
  accountCode: String,
  accountName: String,
  accountType: String,
  parentAccountSubjectId: Option[String],
  level: Int,
  status: String,
  validFrom: LocalDate,
  validTo: Option[LocalDate],
  version: Int
)

case class AccountSubjectEdge(node: AccountSubjectView, cursor: String)
case class AccountSubjectConnection(edges: List[AccountSubjectEdge], pageInfo: PageInfo, totalCount: Int)

case class AccountSubjectFilter(
  accountCode: Option[String],
  accountName: Option[String],
  accountType: Option[String],
  status: Option[String],
  validAt: Option[LocalDate]
)
```

### 9.3.2 勘定科目クエリ定義

```scala
object AccountSubjectQueries {

  val queries = fields[MasterDataContext, Unit](
    Field(
      "accountSubjects",
      AccountSubjectConnectionType,
      description = Some("勘定科目一覧を取得"),
      arguments = List(
        Argument("filter", OptionInputType(AccountSubjectFilterInputType)),
        Argument("page", OptionInputType(IntType), defaultValue = 1),
        Argument("pageSize", OptionInputType(IntType), defaultValue = 50)
      ),
      resolve = ctx => {
        val filter = ctx.argOpt[AccountSubjectFilter]("filter")
        val page = ctx.arg[Int]("page")
        val pageSize = ctx.arg[Int]("pageSize")
        ctx.ctx.accountSubjectRepository.findWithPagination(filter, page, pageSize)
      }
    ),
    Field(
      "accountSubject",
      OptionType(AccountSubjectType),
      description = Some("勘定科目IDで取得"),
      arguments = List(Argument("id", IDType)),
      resolve = ctx => {
        val id = ctx.arg[String]("id")
        ctx.ctx.accountSubjectRepository.findById(UUID.fromString(id))
      }
    ),
    Field(
      "accountSubjectByCode",
      OptionType(AccountSubjectType),
      description = Some("勘定科目コードで取得"),
      arguments = List(Argument("accountCode", StringType)),
      resolve = ctx => {
        val accountCode = ctx.arg[String]("accountCode")
        ctx.ctx.accountSubjectRepository.findByAccountCode(accountCode)
      }
    ),
    Field(
      "accountSubjectTree",
      ListType(AccountSubjectType),
      description = Some("勘定科目階層ツリーを取得"),
      arguments = List(Argument("accountType", AccountTypeType)),
      resolve = ctx => {
        val accountType = ctx.arg[String]("accountType")
        ctx.ctx.accountSubjectRepository.findTreeByAccountType(accountType)
      }
    )
  )
}
```

## 9.4 変更申請API

### 9.4.1 変更申請スキーマ定義

```scala
object ChangeRequestSchema {

  // 変更申請ステータス列挙型
  val ChangeRequestStatusType = EnumType(
    "ChangeRequestStatus",
    values = List(
      EnumValue("PENDING", value = "Pending", description = Some("承認待ち")),
      EnumValue("APPROVED", value = "Approved", description = Some("承認済み")),
      EnumValue("REJECTED", value = "Rejected", description = Some("却下")),
      EnumValue("APPLIED", value = "Applied", description = Some("適用済み")),
      EnumValue("CANCELLED", value = "Cancelled", description = Some("取り消し"))
    )
  )

  // 申請者型
  val RequesterType = ObjectType(
    "Requester",
    fields[Unit, RequesterView](
      Field("userId", StringType, resolve = _.value.userId),
      Field("userName", StringType, resolve = _.value.userName),
      Field("email", StringType, resolve = _.value.email)
    )
  )

  // 承認者型
  val ApproverType = ObjectType(
    "Approver",
    fields[Unit, ApproverView](
      Field("userId", StringType, resolve = _.value.userId),
      Field("userName", StringType, resolve = _.value.userName),
      Field("email", StringType, resolve = _.value.email)
    )
  )

  // 変更申請型
  val ChangeRequestType = ObjectType(
    "ChangeRequest",
    "変更申請",
    fields[MasterDataContext, ChangeRequestView](
      Field("id", IDType, resolve = _.value.id),
      Field("requestType", StringType, resolve = _.value.requestType),
      Field("aggregateType", StringType, resolve = _.value.aggregateType),
      Field("aggregateId", StringType, resolve = _.value.aggregateId),
      Field("requestData", StringType,
        Some("変更内容（JSON）"),
        resolve = _.value.requestData),
      Field("reason", StringType, resolve = _.value.reason),
      Field("requester", RequesterType, resolve = _.value.requester),
      Field("status", ChangeRequestStatusType, resolve = _.value.status),
      Field("requestedAt", DateTimeType, resolve = _.value.requestedAt),
      Field("approver", OptionType(ApproverType), resolve = _.value.approver),
      Field("approvedAt", OptionType(DateTimeType), resolve = _.value.approvedAt),
      Field("rejectionReason", OptionType(StringType), resolve = _.value.rejectionReason),
      Field("appliedAt", OptionType(DateTimeType), resolve = _.value.appliedAt)
    )
  )
}

case class RequesterView(userId: String, userName: String, email: String)
case class ApproverView(userId: String, userName: String, email: String)

case class ChangeRequestView(
  id: String,
  requestType: String,
  aggregateType: String,
  aggregateId: String,
  requestData: String,
  reason: String,
  requester: RequesterView,
  status: String,
  requestedAt: Instant,
  approver: Option[ApproverView],
  approvedAt: Option[Instant],
  rejectionReason: Option[String],
  appliedAt: Option[Instant]
)
```

### 9.4.2 変更申請クエリ・ミューテーション

```scala
object ChangeRequestQueries {

  val queries = fields[MasterDataContext, Unit](
    Field(
      "changeRequests",
      ListType(ChangeRequestType),
      arguments = List(
        Argument("status", OptionInputType(ChangeRequestStatusType)),
        Argument("aggregateType", OptionInputType(StringType))
      ),
      resolve = ctx => {
        val status = ctx.argOpt[String]("status")
        val aggregateType = ctx.argOpt[String]("aggregateType")
        ctx.ctx.changeRequestRepository.find(status, aggregateType)
      }
    ),
    Field(
      "changeRequest",
      OptionType(ChangeRequestType),
      arguments = List(Argument("id", IDType)),
      resolve = ctx => {
        val id = ctx.arg[String]("id")
        ctx.ctx.changeRequestRepository.findById(id)
      }
    ),
    Field(
      "myChangeRequests",
      ListType(ChangeRequestType),
      description = Some("自分が申請した変更申請一覧"),
      resolve = ctx => {
        // 認証ユーザーIDを取得（実装省略）
        val userId = ctx.ctx.currentUser.userId
        ctx.ctx.changeRequestRepository.findByRequester(userId)
      }
    ),
    Field(
      "pendingApprovals",
      ListType(ChangeRequestType),
      description = Some("自分が承認する必要がある変更申請一覧"),
      resolve = ctx => {
        // 認証ユーザーIDを取得
        val userId = ctx.ctx.currentUser.userId
        ctx.ctx.changeRequestRepository.findPendingApprovalsFor(userId)
      }
    )
  )
}

object ChangeRequestMutations {

  val mutations = fields[MasterDataContext, Unit](
    Field(
      "approveChangeRequest",
      ChangeRequestType,
      arguments = List(Argument("requestId", IDType)),
      resolve = ctx => {
        val requestId = ctx.arg[String]("requestId")
        val approver = ctx.ctx.currentUser
        ctx.ctx.approvalService.approve(requestId, approver)
      }
    ),
    Field(
      "rejectChangeRequest",
      ChangeRequestType,
      arguments = List(
        Argument("requestId", IDType),
        Argument("reason", StringType)
      ),
      resolve = ctx => {
        val requestId = ctx.arg[String]("requestId")
        val reason = ctx.arg[String]("reason")
        val approver = ctx.ctx.currentUser
        ctx.ctx.approvalService.reject(requestId, reason, approver)
      }
    ),
    Field(
      "cancelChangeRequest",
      ChangeRequestType,
      arguments = List(Argument("requestId", IDType)),
      resolve = ctx => {
        val requestId = ctx.arg[String]("requestId")
        val requester = ctx.ctx.currentUser
        ctx.ctx.approvalService.cancel(requestId, requester)
      }
    )
  )
}
```

## 9.5 DataLoaderによるN+1問題の解決

### 9.5.1 DataLoader実装

```scala
package com.example.masterdata.query.graphql.dataloader

import scala.concurrent.{ExecutionContext, Future}
import scala.collection.mutable

/**
 * DataLoader（N+1クエリ問題を解決）
 */
class DataLoader[K, V](
  batchLoadFn: Seq[K] => Future[Map[K, V]]
)(implicit ec: ExecutionContext) {

  private val cache = mutable.Map[K, Future[V]]()
  private val queue = mutable.Queue[K]()
  private var dispatched = false

  def load(key: K): Future[V] = {
    cache.get(key) match {
      case Some(cached) => cached
      case None =>
        queue.enqueue(key)
        val future = scheduleDispatch().flatMap { resultMap =>
          resultMap.get(key) match {
            case Some(value) => Future.successful(value)
            case None => Future.failed(new NoSuchElementException(s"Key not found: $key"))
          }
        }
        cache.put(key, future)
        future
    }
  }

  def loadMany(keys: Seq[K]): Future[Seq[V]] = {
    Future.sequence(keys.map(load))
  }

  private def scheduleDispatch(): Future[Map[K, V]] = {
    if (!dispatched && queue.nonEmpty) {
      dispatched = true
      val keys = queue.toSeq
      queue.clear()

      batchLoadFn(keys).map { results =>
        dispatched = false
        results
      }
    } else {
      Future.successful(Map.empty)
    }
  }

  def clear(): Unit = {
    cache.clear()
    queue.clear()
    dispatched = false
  }
}

/**
 * DataLoaderファクトリ
 */
object DataLoaders {

  def createProductPriceLoader(
    productPriceRepository: ProductPriceRepository
  )(implicit ec: ExecutionContext): DataLoader[String, List[ProductPriceView]] = {
    new DataLoader[String, List[ProductPriceView]](productIds => {
      productPriceRepository.findByProductIds(productIds.toList).map { prices =>
        prices.groupBy(_.productId).map { case (productId, priceList) =>
          productId -> priceList
        }
      }
    })
  }

  def createAccountSubjectLoader(
    accountSubjectRepository: AccountSubjectRepository
  )(implicit ec: ExecutionContext): DataLoader[String, Option[AccountSubjectView]] = {
    new DataLoader[String, Option[AccountSubjectView]](ids => {
      accountSubjectRepository.findByIds(ids.toList).map { accountSubjects =>
        accountSubjects.map(as => as.id -> Some(as)).toMap
      }
    })
  }
}
```

### 9.5.2 コンテキストへのDataLoader統合

```scala
case class MasterDataContext(
  productRepository: ProductQueryRepository,
  productPriceRepository: ProductPriceQueryRepository,
  accountSubjectRepository: AccountSubjectQueryRepository,
  changeRequestRepository: ChangeRequestQueryRepository,
  productCommandService: ProductCommandService,
  approvalService: ApprovalService,
  currentUser: User,
  productPriceLoader: DataLoader[String, List[ProductPriceView]],
  accountSubjectLoader: DataLoader[String, Option[AccountSubjectView]]
)(implicit ec: ExecutionContext) {

  // リクエスト終了時にDataLoaderをクリア
  def clearLoaders(): Unit = {
    productPriceLoader.clear()
    accountSubjectLoader.clear()
  }
}
```

## 9.6 GraphQLエンドポイント実装

### 9.6.1 Pekko HTTPエンドポイント

```scala
package com.example.masterdata.query.api

import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.model.StatusCodes
import sangria.execution._
import sangria.parser.QueryParser
import sangria.marshalling.circe._
import io.circe.Json
import io.circe.parser._
import scala.concurrent.ExecutionContext
import scala.util.{Success, Failure}

/**
 * GraphQLエンドポイント
 */
class GraphQLRoute(
  schema: Schema[MasterDataContext, Unit],
  createContext: () => MasterDataContext
)(implicit ec: ExecutionContext) {

  val route: Route = {
    path("graphql") {
      post {
        entity(as[String]) { requestBody =>
          parse(requestBody) match {
            case Right(json) =>
              val query = json.hcursor.get[String]("query").getOrElse("")
              val operationName = json.hcursor.get[String]("operationName").toOption
              val variables = json.hcursor.get[Json]("variables").getOrElse(Json.obj())

              QueryParser.parse(query) match {
                case Success(queryAst) =>
                  val ctx = createContext()

                  val result = Executor.execute(
                    schema = schema,
                    queryAst = queryAst,
                    userContext = ctx,
                    variables = variables,
                    operationName = operationName,
                    exceptionHandler = exceptionHandler
                  ).map { json =>
                    ctx.clearLoaders() // DataLoaderをクリア
                    json
                  }

                  complete(result)

                case Failure(error) =>
                  complete(StatusCodes.BadRequest, s"GraphQLクエリのパースエラー: ${error.getMessage}")
              }

            case Left(error) =>
              complete(StatusCodes.BadRequest, s"JSONパースエラー: ${error.getMessage}")
          }
        }
      } ~
      get {
        // GraphQL Playgroundを表示
        getFromResource("graphql-playground.html")
      }
    }
  }

  private val exceptionHandler = ExceptionHandler {
    case (m, e: Exception) =>
      HandledException(e.getMessage)
  }
}
```

### 9.6.2 GraphQL Playground HTML

```html
<!-- src/main/resources/graphql-playground.html -->
<!DOCTYPE html>
<html>
<head>
  <meta charset=utf-8/>
  <meta name="viewport" content="user-scalable=no, initial-scale=1.0, minimum-scale=1.0, maximum-scale=1.0, minimal-ui">
  <title>GraphQL Playground</title>
  <link rel="stylesheet" href="//cdn.jsdelivr.net/npm/graphql-playground-react/build/static/css/index.css" />
  <link rel="shortcut icon" href="//cdn.jsdelivr.net/npm/graphql-playground-react/build/favicon.png" />
  <script src="//cdn.jsdelivr.net/npm/graphql-playground-react/build/static/js/middleware.js"></script>
</head>
<body>
  <div id="root">
    <style>
      body {
        background-color: rgb(23, 42, 58);
        font-family: Open Sans, sans-serif;
        height: 90vh;
      }
      #root {
        height: 100%;
        width: 100%;
        display: flex;
        align-items: center;
        justify-content: center;
      }
      .loading {
        font-size: 32px;
        font-weight: 200;
        color: rgba(255, 255, 255, .6);
        margin-left: 20px;
      }
      img {
        width: 78px;
        height: 78px;
      }
      .title {
        font-weight: 400;
      }
    </style>
    <img src='//cdn.jsdelivr.net/npm/graphql-playground-react/build/logo.png' alt=''>
    <div class="loading"> Loading
      <span class="title">GraphQL Playground</span>
    </div>
  </div>
  <script>window.addEventListener('load', function (event) {
      GraphQLPlayground.init(document.getElementById('root'), {
        endpoint: '/graphql'
      })
    })</script>
</body>
</html>
```

## 9.7 まとめ

本章では、共用データ管理サービスのGraphQL APIを詳細に実装しました。

### 実装した内容

1. **GraphQLスキーマの全体設計**: 共通型定義、カスタムスカラー型
2. **商品マスターAPI**: 商品CRUD、価格管理、検索機能
3. **勘定科目マスターAPI**: 階層構造対応、ツリー表示
4. **変更申請API**: 承認ワークフロー対応
5. **DataLoaderによるN+1問題解決**: バッチ処理による効率化
6. **GraphQLエンドポイント**: Pekko HTTP統合、GraphQL Playground

### 次章の予告

次の第10章では、パフォーマンス最適化を詳しく解説します。キャッシング戦略、インデックス最適化、Materialized Viewの活用により、大規模データでも高速に動作するシステムを構築します。
