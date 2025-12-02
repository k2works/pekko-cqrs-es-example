# 【第4部 第13章】高度なトピック：システム連携と分散トレーシング

## 本章の目的

本章では、受注管理システムをさらに発展させるための高度なトピックについて説明します。イベント駆動による他システム連携、CQRS/Event Sourcingの高度なパターン、分散トレーシングの実装方法を学びます。

## 13.1 イベント駆動による他システム連携

### 13.1.1 配送管理システムとの連携

OrderShippedイベントを配送管理システムへ通知し、配送状況を追跡します。

```scala
package com.example.order.adapter.integration

import com.example.order.domain._
import com.example.order.adapter.actor.OrderActor
import org.apache.pekko.persistence.query._
import org.apache.pekko.stream.scaladsl._
import zio._
import io.circe.syntax._

// 配送管理システムAPI
trait ShippingManagementClient {
  def createShippingInstruction(instruction: ShippingInstruction): Task[ShippingInstructionId]
  def updateDeliveryStatus(instructionId: ShippingInstructionId, status: DeliveryStatus): Task[Unit]
}

// 配送指示
final case class ShippingInstruction(
  orderId: OrderId,
  customerId: CustomerId,
  shippingAddress: Address,
  items: List[ShippingItem],
  requestedDeliveryDate: Option[java.time.LocalDate]
)

final case class ShippingItem(
  productId: ProductId,
  productName: String,
  quantity: Quantity
)

final case class ShippingInstructionId(value: String) extends AnyVal

sealed trait DeliveryStatus
object DeliveryStatus {
  case object Preparing extends DeliveryStatus
  case object InTransit extends DeliveryStatus
  case object Delivered extends DeliveryStatus
  case object Failed extends DeliveryStatus
}

// 配送連携プロジェクション
class ShippingIntegrationProjection(
  eventsByTagQuery: EventsByTagQuery,
  shippingClient: ShippingManagementClient,
  orderRepository: OrderRepository
) {

  // OrderShippedイベントを購読して配送指示を作成
  def start(): Task[Unit] = {
    ZIO.attemptBlocking {
      eventsByTagQuery
        .eventsByTag("order-shipped", NoOffset)
        .mapAsync(parallelism = 4) { envelope =>
          envelope.event match {
            case OrderActor.OrderShipped(shippedQuantities, shippedAt, _) =>
              handleOrderShipped(extractOrderId(envelope), shippedQuantities).runToFuture

            case _ =>
              Future.successful(())
          }
        }
        .runForeach { _ => () }
    }
  }

  private def handleOrderShipped(
    orderId: OrderId,
    shippedQuantities: Map[ProductId, Quantity]
  ): Task[Unit] = {
    for {
      // 注文情報を取得
      order <- orderRepository.findById(orderId).someOrFail(
        new RuntimeException(s"Order not found: ${orderId.value}")
      )

      // 配送指示を作成
      instruction = ShippingInstruction(
        orderId = order.id,
        customerId = order.customerId,
        shippingAddress = order.shippingAddress.getOrElse(
          throw new RuntimeException("Shipping address not found")
        ),
        items = order.items.map { item =>
          ShippingItem(
            productId = item.productId,
            productName = item.productName,
            quantity = shippedQuantities.getOrElse(item.productId, item.quantity)
          )
        },
        requestedDeliveryDate = order.requestedDeliveryDate
      )

      // 配送管理システムへ送信
      instructionId <- shippingClient.createShippingInstruction(instruction)

      _ <- ZIO.logInfo(
        s"Created shipping instruction ${instructionId.value} for order ${orderId.value}"
      )
    } yield ()
  }

  private def extractOrderId(envelope: EventEnvelope): OrderId = {
    val persistenceId = envelope.persistenceId
    val orderId = persistenceId.split("-").last
    OrderId(orderId)
  }
}

// HTTP実装例
class HttpShippingManagementClient(
  baseUrl: String,
  httpClient: sttp.client3.SttpBackend[Task, Any]
) extends ShippingManagementClient {

  import sttp.client3._

  def createShippingInstruction(
    instruction: ShippingInstruction
  ): Task[ShippingInstructionId] = {
    val request = basicRequest
      .post(uri"$baseUrl/shipping-instructions")
      .body(instruction.asJson.noSpaces)
      .contentType("application/json")
      .response(asJson[ShippingInstructionResponse])

    ZIO.fromEither(
      httpClient.send(request).map { response =>
        response.body.map(_.instructionId)
      }
    ).flatten
  }

  def updateDeliveryStatus(
    instructionId: ShippingInstructionId,
    status: DeliveryStatus
  ): Task[Unit] = {
    val request = basicRequest
      .patch(uri"$baseUrl/shipping-instructions/${instructionId.value}/status")
      .body(Map("status" -> status.toString).asJson.noSpaces)
      .contentType("application/json")

    ZIO.fromEither(
      httpClient.send(request).map(_ => ())
    ).flatten
  }
}

final case class ShippingInstructionResponse(instructionId: ShippingInstructionId)
```

### 13.1.2 会計システムとの連携

InvoiceGeneratedイベントを会計システムへ通知し、売上を計上します。

```scala
package com.example.order.adapter.integration

import com.example.order.domain._
import com.example.order.adapter.actor.InvoiceActor
import org.apache.pekko.persistence.query._
import zio._
import java.time.YearMonth

// 会計システムAPI
trait AccountingSystemClient {
  def recordRevenue(entry: RevenueEntry): Task[JournalEntryId]
  def recordReceivable(entry: ReceivableEntry): Task[JournalEntryId]
  def recordPayment(entry: PaymentEntry): Task[JournalEntryId]
}

// 売上計上エントリ
final case class RevenueEntry(
  invoiceId: InvoiceId,
  customerId: CustomerId,
  billingYearMonth: YearMonth,
  revenueAmount: Money,
  taxAmount: Money,
  totalAmount: Money,
  accountingDate: java.time.LocalDate
)

// 売掛金計上エントリ
final case class ReceivableEntry(
  invoiceId: InvoiceId,
  customerId: CustomerId,
  amount: Money,
  dueDate: java.time.LocalDate
)

// 入金計上エントリ
final case class PaymentEntry(
  invoiceId: InvoiceId,
  customerId: CustomerId,
  paymentAmount: Money,
  paymentDate: java.time.LocalDate,
  paymentMethod: PaymentMethod
)

final case class JournalEntryId(value: String) extends AnyVal

// 会計連携プロジェクション
class AccountingIntegrationProjection(
  eventsByTagQuery: EventsByTagQuery,
  accountingClient: AccountingSystemClient,
  invoiceRepository: InvoiceRepository
) {

  // InvoiceGeneratedイベントを購読して売上計上
  def start(): Task[Unit] = {
    ZIO.attemptBlocking {
      eventsByTagQuery
        .eventsByTag("invoice-generated", NoOffset)
        .mapAsync(parallelism = 4) { envelope =>
          envelope.event match {
            case InvoiceActor.InvoiceGenerated(invoiceId, customerId, billingYearMonth, orderIds, totalAmount, issueDate, dueDate, _) =>
              handleInvoiceGenerated(invoiceId, customerId, billingYearMonth, totalAmount, dueDate).runToFuture

            case InvoiceActor.PaymentRecorded(payment, _) =>
              handlePaymentRecorded(extractInvoiceId(envelope), payment).runToFuture

            case _ =>
              Future.successful(())
          }
        }
        .runForeach { _ => () }
    }
  }

  private def handleInvoiceGenerated(
    invoiceId: InvoiceId,
    customerId: CustomerId,
    billingYearMonth: YearMonth,
    totalAmount: Money,
    dueDate: java.time.LocalDate
  ): Task[Unit] = {
    for {
      // 請求書詳細を取得
      invoice <- invoiceRepository.findById(invoiceId).someOrFail(
        new RuntimeException(s"Invoice not found: ${invoiceId.value}")
      )

      // 売上を計上（借方: 売掛金、貸方: 売上高）
      revenueEntry = RevenueEntry(
        invoiceId = invoiceId,
        customerId = customerId,
        billingYearMonth = billingYearMonth,
        revenueAmount = invoice.totalAmount - calculateTaxAmount(invoice),
        taxAmount = calculateTaxAmount(invoice),
        totalAmount = totalAmount,
        accountingDate = java.time.LocalDate.now()
      )

      revenueJournalId <- accountingClient.recordRevenue(revenueEntry)

      // 売掛金を計上
      receivableEntry = ReceivableEntry(
        invoiceId = invoiceId,
        customerId = customerId,
        amount = totalAmount,
        dueDate = dueDate
      )

      receivableJournalId <- accountingClient.recordReceivable(receivableEntry)

      _ <- ZIO.logInfo(
        s"Recorded revenue (${revenueJournalId.value}) and receivable (${receivableJournalId.value}) for invoice ${invoiceId.value}"
      )
    } yield ()
  }

  private def handlePaymentRecorded(
    invoiceId: InvoiceId,
    payment: Payment
  ): Task[Unit] = {
    for {
      invoice <- invoiceRepository.findById(invoiceId).someOrFail(
        new RuntimeException(s"Invoice not found: ${invoiceId.value}")
      )

      // 入金を計上（借方: 現金、貸方: 売掛金）
      paymentEntry = PaymentEntry(
        invoiceId = invoiceId,
        customerId = invoice.customerId,
        paymentAmount = payment.amount,
        paymentDate = payment.paymentDate,
        paymentMethod = payment.paymentMethod
      )

      journalId <- accountingClient.recordPayment(paymentEntry)

      _ <- ZIO.logInfo(
        s"Recorded payment (${journalId.value}) for invoice ${invoiceId.value}"
      )
    } yield ()
  }

  private def calculateTaxAmount(invoice: Invoice): Money = {
    // 注文から税額を集計（実際には注文情報を取得する必要がある）
    // 簡略化のため、総額の10%と仮定
    (invoice.totalAmount * BigDecimal("0.0909")).round(0)  // 税込から税抜への逆算
  }

  private def extractInvoiceId(envelope: EventEnvelope): InvoiceId = {
    val persistenceId = envelope.persistenceId
    val invoiceId = persistenceId.split("-").last
    InvoiceId(invoiceId)
  }
}
```

## 13.2 CQRS/Event Sourcingの高度なパターン

### 13.2.1 Event-Carried State Transfer

イベントに十分な情報を含めることで、他のコンテキストがイベントストリームから状態を再構築できるようにします。

```scala
package com.example.order.domain.event

import com.example.order.domain._
import java.time.Instant

// リッチなイベント（Event-Carried State Transfer）
final case class OrderCreatedV2(
  // 基本情報
  orderId: OrderId,
  customerId: CustomerId,
  companyId: CompanyId,
  orderNumber: OrderNumber,
  orderDate: java.time.LocalDate,

  // 顧客情報（スナップショット）
  customerName: String,
  customerType: CustomerType,
  customerEmail: String,

  // 注文明細（完全な情報）
  items: List[OrderItemDetail],

  // 金額情報
  subtotalAmount: Money,
  discountAmount: Money,
  taxAmount: Money,
  totalAmount: Money,

  // 配送情報
  shippingAddress: Option[Address],
  requestedDeliveryDate: Option[java.time.LocalDate],

  occurredAt: Instant = Instant.now()
)

// 注文明細の詳細情報
final case class OrderItemDetail(
  orderItemId: OrderItemId,
  productId: ProductId,
  productName: String,
  productCategory: String,
  quantity: Quantity,
  unitPrice: Money,
  discountRate: Option[DiscountRate],
  taxCategory: TaxCategory,
  taxRate: TaxRate,
  subtotal: Money,
  discountAmount: Money,
  taxAmount: Money,
  totalAmount: Money
)

// このイベントがあれば、他のコンテキストは以下の情報を取得できる：
// - 注文の基本情報
// - 顧客の基本情報（別途顧客サービスへ問い合わせ不要）
// - 注文明細の完全な情報
// - 計算済みの金額情報

// 売上分析サービスでの利用例
class SalesAnalyticsProjection(
  eventsByTagQuery: EventsByTagQuery
) {

  def buildSalesReport(): Task[Unit] = {
    ZIO.attemptBlocking {
      eventsByTagQuery
        .eventsByTag("order-created", NoOffset)
        .mapAsync(parallelism = 4) { envelope =>
          envelope.event match {
            case event: OrderCreatedV2 =>
              // イベントに含まれる情報だけで売上分析が可能
              updateSalesMetrics(event).runToFuture

            case _ =>
              Future.successful(())
          }
        }
        .runForeach { _ => () }
    }
  }

  private def updateSalesMetrics(event: OrderCreatedV2): Task[Unit] = {
    for {
      _ <- ZIO.logInfo(s"Updating sales metrics for order ${event.orderId.value}")

      // 顧客別売上
      _ <- updateCustomerSales(
        customerId = event.customerId,
        customerName = event.customerName,
        amount = event.totalAmount
      )

      // 商品別売上
      _ <- ZIO.foreach_(event.items) { item =>
        updateProductSales(
          productId = item.productId,
          productName = item.productName,
          productCategory = item.productCategory,
          quantity = item.quantity.value,
          amount = item.totalAmount
        )
      }

      // 日次売上
      _ <- updateDailySales(
        date = event.orderDate,
        amount = event.totalAmount
      )
    } yield ()
  }

  private def updateCustomerSales(customerId: CustomerId, customerName: String, amount: Money): Task[Unit] = {
    // 実装省略
    ZIO.unit
  }

  private def updateProductSales(productId: ProductId, productName: String, productCategory: String, quantity: Int, amount: Money): Task[Unit] = {
    // 実装省略
    ZIO.unit
  }

  private def updateDailySales(date: java.time.LocalDate, amount: Money): Task[Unit] = {
    // 実装省略
    ZIO.unit
  }
}
```

### 13.2.2 Snapshot最適化

イベント数が多い集約に対して、効率的なスナップショット戦略を実装します。

```scala
package com.example.order.adapter.actor

import org.apache.pekko.persistence.typed.scaladsl._
import com.example.order.domain._

object OptimizedOrderActor {

  // スナップショット戦略のカスタマイズ
  def apply(orderId: OrderId): Behavior[Command] = {
    EventSourcedBehavior[Command, Event, State](
      persistenceId = PersistenceId.ofUniqueId(s"Order-${orderId.value}"),
      emptyState = EmptyState,
      commandHandler = commandHandler,
      eventHandler = eventHandler
    )
      .withRetention(
        // 動的なスナップショット戦略
        RetentionCriteria
          .snapshotEvery(numberOfEvents = 50, keepNSnapshots = 3)
          .withDeleteEventsOnSnapshot
      )
      .snapshotWhen { (state, event, sequenceNumber) =>
        // 条件付きスナップショット
        event match {
          // 重要なイベント後は必ずスナップショット
          case _: OrderConfirmed => true
          case _: OrderShipped => true
          case _: DeliveryCompleted => true

          // それ以外は50イベントごと
          case _ => sequenceNumber % 50 == 0
        }
      }
  }

  // 軽量なスナップショット用の状態
  final case class OrderSnapshot(
    id: OrderId,
    customerId: CustomerId,
    orderNumber: OrderNumber,
    status: OrderStatus,
    totalAmount: Money,
    version: Version
    // 注文明細などの詳細は省略（イベントから復元）
  )

  // スナップショットの保存
  def toSnapshot(order: Order): OrderSnapshot = {
    OrderSnapshot(
      id = order.id,
      customerId = order.customerId,
      orderNumber = order.orderNumber,
      status = order.status,
      totalAmount = order.totalAmount,
      version = order.version
    )
  }

  // スナップショットからの復元
  def fromSnapshot(snapshot: OrderSnapshot, events: List[Event]): Order = {
    // スナップショット後のイベントを再生して完全な状態を復元
    events.foldLeft(initialOrderFromSnapshot(snapshot)) { (order, event) =>
      applyEvent(order, event)
    }
  }

  private def initialOrderFromSnapshot(snapshot: OrderSnapshot): Order = {
    Order(
      id = snapshot.id,
      customerId = snapshot.customerId,
      companyId = CompanyId("unknown"),  // 後のイベントから復元
      orderNumber = snapshot.orderNumber,
      orderDate = java.time.LocalDate.now(),  // 後のイベントから復元
      quotationId = None,
      items = List.empty,  // 後のイベントから復元
      shippingAddress = None,
      requestedDeliveryDate = None,
      status = snapshot.status,
      sagaId = None,
      version = snapshot.version
    )
  }
}
```

## 13.3 分散トレーシング

### 13.3.1 OpenTelemetryによる分散トレース

Sagaステップごとのレイテンシを可視化します。

```scala
package com.example.order.monitoring

import io.opentelemetry.api.trace.{Span, SpanKind, Tracer}
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.context.Context
import zio._

class DistributedTracingService {

  private val tracer: Tracer = GlobalOpenTelemetry.getTracer("order-management-service")

  // Sagaのトレース
  def traceSaga[A](sagaId: SagaId, operation: Task[A]): Task[A] = {
    ZIO.scoped {
      for {
        span <- createSpan(
          name = s"saga.${sagaId.value}",
          kind = SpanKind.INTERNAL
        )
        _ <- addAttribute(span, "saga.id", sagaId.value)
        result <- operation
          .onError { cause =>
            recordException(span, cause)
          }
      } yield result
    }
  }

  // Sagaステップのトレース
  def traceSagaStep[A](
    sagaId: SagaId,
    step: SagaStep,
    operation: Task[A]
  ): Task[A] = {
    ZIO.scoped {
      for {
        span <- createSpan(
          name = s"saga.step.${step.toString}",
          kind = SpanKind.INTERNAL
        )
        _ <- addAttribute(span, "saga.id", sagaId.value)
        _ <- addAttribute(span, "saga.step", step.toString)

        start <- Clock.nanoTime
        result <- operation
          .onError { cause =>
            recordException(span, cause)
          }
        end <- Clock.nanoTime

        duration = (end - start) / 1000000  // ナノ秒からミリ秒へ
        _ <- addAttribute(span, "saga.step.duration_ms", duration.toString)
      } yield result
    }
  }

  // 与信チェックのトレース
  def traceCreditCheck[A](
    customerId: CustomerId,
    orderAmount: Money,
    operation: Task[A]
  ): Task[A] = {
    ZIO.scoped {
      for {
        span <- createSpan(
          name = "credit.check",
          kind = SpanKind.INTERNAL
        )
        _ <- addAttribute(span, "customer.id", customerId.value)
        _ <- addAttribute(span, "order.amount", orderAmount.amount.toString)

        start <- Clock.nanoTime
        result <- operation
        end <- Clock.nanoTime

        duration = (end - start) / 1000000
        _ <- addAttribute(span, "credit.check.duration_ms", duration.toString)
      } yield result
    }
  }

  private def createSpan(name: String, kind: SpanKind): Task[Span] = {
    ZIO.attempt {
      val span = tracer.spanBuilder(name)
        .setSpanKind(kind)
        .startSpan()
      span
    }
  }

  private def addAttribute(span: Span, key: String, value: String): Task[Unit] = {
    ZIO.attempt {
      span.setAttribute(key, value)
    }
  }

  private def recordException(span: Span, cause: Cause[Any]): Task[Unit] = {
    ZIO.attempt {
      val exception = cause.defects.headOption.getOrElse(
        new RuntimeException(cause.prettyPrint)
      )
      span.recordException(exception)
    }
  }
}

// Sagaオーケストレーターへの統合
class TracedOrderSagaOrchestrator(
  // ... 既存のフィールド ...
  tracingService: DistributedTracingService
) {

  private def executeStep(
    sagaId: SagaId,
    step: SagaStep,
    operation: Task[Unit]
  ): Task[Unit] = {
    tracingService.traceSagaStep(sagaId, step, operation)
  }

  // ステップ2: 在庫引当（トレース付き）
  private def reserveStock(orderId: OrderId, sagaId: SagaId): Task[Unit] = {
    executeStep(sagaId, SagaStep.StockReserved) {
      // 在庫引当処理
      ZIO.unit
    }
  }

  // ステップ3: 与信チェック（トレース付き）
  private def checkCredit(
    customerId: CustomerId,
    orderAmount: Money,
    sagaId: SagaId
  ): Task[Unit] = {
    executeStep(sagaId, SagaStep.CreditApproved) {
      tracingService.traceCreditCheck(customerId, orderAmount) {
        // 与信チェック処理
        ZIO.unit
      }
    }
  }
}
```

### 13.3.2 Jaeger UIでの可視化

```yaml
# docker-compose.yaml に追加
services:
  jaeger:
    image: jaegertracing/all-in-one:latest
    ports:
      - "5775:5775/udp"
      - "6831:6831/udp"
      - "6832:6832/udp"
      - "5778:5778"
      - "16686:16686"  # UI
      - "14268:14268"
      - "14250:14250"
      - "9411:9411"
    environment:
      - COLLECTOR_ZIPKIN_HOST_PORT=:9411

# application.conf に追加
otel {
  service.name = "order-management-service"
  exporter.jaeger.endpoint = "http://localhost:14250"
}
```

## 13.4 まとめ

本章では、受注管理システムを発展させるための高度なトピックについて学びました。

**イベント駆動システム連携**:
- Event-Carried State Transferパターンによる他システムとの疎結合な連携
- 配送管理システムとの非同期連携（OrderShippedイベント）
- 会計システムとの非同期連携（InvoiceGeneratedイベント）

**CQRS/Event Sourcingの高度なパターン**:
- イベントに十分な情報を含めることで、他コンテキストでの状態再構築を可能にする
- Snapshot最適化によるパフォーマンス改善（条件付きスナップショット、軽量スナップショット）
- イベント数が多い集約の効率的な処理

**分散トレーシング**:
- OpenTelemetryによるSagaステップのトレーシング
- Jaegerを使った分散トランザクションの可視化
- パフォーマンスボトルネックの特定と改善

これらの高度なトピックを活用することで、受注管理システムはより拡張性が高く、他システムとの連携も容易なエンタープライズシステムへと進化します。

次章では、Part 4全体を振り返り、学んだ知識を実践するための演習を提供します。
