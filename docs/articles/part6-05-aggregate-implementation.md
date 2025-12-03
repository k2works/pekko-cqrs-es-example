# 第6部 第5章 複数集約の実装

本章では、会計システムの主要な集約をPekko Persistenceを使用してイベントソーシングで実装します。

## 5.1 JournalEntry集約の実装

仕訳集約は、会計システムの中核となる集約です。全てのビジネストランザクションは仕訳として記録されます。

### 5.1.1 コマンドとイベントの定義

```scala
// JournalEntry コマンド
sealed trait JournalEntryCommand

// 仕訳作成
final case class CreateJournalEntry(
  journalEntryId: JournalEntryId,
  transactionDate: LocalDate,
  entryDate: LocalDate,
  fiscalPeriod: FiscalPeriod,
  voucherType: VoucherType,
  voucherNumber: Option[VoucherNumber],
  lines: List[JournalEntryLineData],
  description: String,
  referenceInfo: Option[ReferenceInfo],
  createdBy: UserId,
  replyTo: ActorRef[StatusReply[JournalEntryCreated]]
) extends JournalEntryCommand

// 承認申請
final case class RequestApproval(
  journalEntryId: JournalEntryId,
  requestedBy: UserId,
  replyTo: ActorRef[StatusReply[ApprovalRequested]]
) extends JournalEntryCommand

// 承認
final case class ApproveJournalEntry(
  journalEntryId: JournalEntryId,
  approver: UserId,
  comment: Option[String],
  replyTo: ActorRef[StatusReply[JournalEntryApproved]]
) extends JournalEntryCommand

// 却下
final case class RejectJournalEntry(
  journalEntryId: JournalEntryId,
  rejectedBy: UserId,
  reason: String,
  replyTo: ActorRef[StatusReply[JournalEntryRejected]]
) extends JournalEntryCommand

// 転記
final case class PostJournalEntry(
  journalEntryId: JournalEntryId,
  postedBy: UserId,
  replyTo: ActorRef[StatusReply[JournalEntryPosted]]
) extends JournalEntryCommand

// 取消
final case class CancelJournalEntry(
  journalEntryId: JournalEntryId,
  reason: String,
  cancelledBy: UserId,
  replyTo: ActorRef[StatusReply[JournalEntryCancelled]]
) extends JournalEntryCommand

// JournalEntry イベント
sealed trait JournalEntryEvent

// 仕訳作成イベント
final case class JournalEntryCreated(
  journalEntryId: JournalEntryId,
  entryNumber: EntryNumber,
  transactionDate: LocalDate,
  entryDate: LocalDate,
  fiscalPeriod: FiscalPeriod,
  voucherType: VoucherType,
  voucherNumber: Option[VoucherNumber],
  lines: List[JournalEntryLineData],
  description: String,
  totalAmount: Money,
  referenceInfo: Option[ReferenceInfo],
  createdBy: UserId,
  createdAt: Instant
) extends JournalEntryEvent

// 承認申請イベント
final case class ApprovalRequested(
  journalEntryId: JournalEntryId,
  requestedBy: UserId,
  requestedAt: Instant
) extends JournalEntryEvent

// 承認完了イベント
final case class JournalEntryApproved(
  journalEntryId: JournalEntryId,
  approver: UserId,
  approvedAt: Instant,
  comment: Option[String]
) extends JournalEntryEvent

// 承認却下イベント
final case class JournalEntryRejected(
  journalEntryId: JournalEntryId,
  rejectedBy: UserId,
  rejectedAt: Instant,
  reason: String
) extends JournalEntryEvent

// 転記完了イベント
final case class JournalEntryPosted(
  journalEntryId: JournalEntryId,
  postedBy: UserId,
  postedAt: Instant,
  ledgerEntryIds: List[LedgerEntryId]
) extends JournalEntryEvent

// 取消イベント
final case class JournalEntryCancelled(
  journalEntryId: JournalEntryId,
  reversalEntryId: JournalEntryId,
  reason: String,
  cancelledBy: UserId,
  cancelledAt: Instant
) extends JournalEntryEvent

// 仕訳明細データ（コマンド・イベント用）
final case class JournalEntryLineData(
  accountCode: AccountCode,
  debitCredit: DebitCredit,
  amount: Money,
  taxInfo: Option[TaxInfo],
  auxiliaryAccount: Option[AuxiliaryAccount],
  description: Option[String]
)
```

### 5.1.2 JournalEntryActor の実装

```scala
object JournalEntryActor {

  // State
  sealed trait State
  case object EmptyState extends State
  final case class JournalEntryState(entry: JournalEntry) extends State

  def apply(
    journalEntryId: JournalEntryId,
    entryNumberGenerator: () => EntryNumber,
    chartOfAccountsService: ChartOfAccountsService
  ): Behavior[JournalEntryCommand] = {
    Behaviors.setup { context =>
      EventSourcedBehavior[JournalEntryCommand, JournalEntryEvent, State](
        persistenceId = PersistenceId.ofUniqueId(s"JournalEntry-${journalEntryId.value}"),
        emptyState = EmptyState,
        commandHandler = commandHandler(entryNumberGenerator, chartOfAccountsService, context),
        eventHandler = eventHandler
      ).withRetention(RetentionCriteria.snapshotEvery(numberOfEvents = 100, keepNSnapshots = 2))
    }
  }

  // Command Handler
  private def commandHandler(
    entryNumberGenerator: () => EntryNumber,
    chartOfAccountsService: ChartOfAccountsService,
    context: ActorContext[JournalEntryCommand]
  ): (State, JournalEntryCommand) => Effect[JournalEntryEvent, State] = {

    // 仕訳作成
    case (EmptyState, cmd: CreateJournalEntry) =>
      // ビジネスルールの検証
      validateJournalEntry(cmd, chartOfAccountsService) match {
        case Left(error) =>
          Effect.none.thenRun { _ =>
            cmd.replyTo ! StatusReply.Error(error.message)
          }

        case Right(_) =>
          val entryNumber = entryNumberGenerator()
          val totalAmount = calculateTotalAmount(cmd.lines)

          val event = JournalEntryCreated(
            journalEntryId = cmd.journalEntryId,
            entryNumber = entryNumber,
            transactionDate = cmd.transactionDate,
            entryDate = cmd.entryDate,
            fiscalPeriod = cmd.fiscalPeriod,
            voucherType = cmd.voucherType,
            voucherNumber = cmd.voucherNumber,
            lines = cmd.lines,
            description = cmd.description,
            totalAmount = totalAmount,
            referenceInfo = cmd.referenceInfo,
            createdBy = cmd.createdBy,
            createdAt = Instant.now()
          )

          Effect
            .persist(event)
            .thenRun { _ =>
              cmd.replyTo ! StatusReply.Success(event)
            }
      }

    // 既に存在する場合
    case (JournalEntryState(_), cmd: CreateJournalEntry) =>
      Effect.none.thenRun { _ =>
        cmd.replyTo ! StatusReply.Error("仕訳は既に作成されています")
      }

    // 承認申請
    case (JournalEntryState(entry), cmd: RequestApproval) =>
      if (entry.status != JournalEntryStatus.Draft) {
        Effect.none.thenRun { _ =>
          cmd.replyTo ! StatusReply.Error(s"承認申請できません。現在のステータス: ${entry.status}")
        }
      } else if (!entry.isBalanced) {
        Effect.none.thenRun { _ =>
          cmd.replyTo ! StatusReply.Error("仕訳の貸借が一致していません")
        }
      } else {
        val event = ApprovalRequested(
          journalEntryId = cmd.journalEntryId,
          requestedBy = cmd.requestedBy,
          requestedAt = Instant.now()
        )

        Effect
          .persist(event)
          .thenRun { _ =>
            cmd.replyTo ! StatusReply.Success(event)
          }
      }

    // 承認
    case (JournalEntryState(entry), cmd: ApproveJournalEntry) =>
      if (!entry.canBeApproved) {
        Effect.none.thenRun { _ =>
          cmd.replyTo ! StatusReply.Error(s"承認できません。現在のステータス: ${entry.status}")
        }
      } else {
        val event = JournalEntryApproved(
          journalEntryId = cmd.journalEntryId,
          approver = cmd.approver,
          approvedAt = Instant.now(),
          comment = cmd.comment
        )

        Effect
          .persist(event)
          .thenRun { _ =>
            cmd.replyTo ! StatusReply.Success(event)
          }
      }

    // 却下
    case (JournalEntryState(entry), cmd: RejectJournalEntry) =>
      if (entry.status != JournalEntryStatus.PendingApproval) {
        Effect.none.thenRun { _ =>
          cmd.replyTo ! StatusReply.Error(s"却下できません。現在のステータス: ${entry.status}")
        }
      } else {
        val event = JournalEntryRejected(
          journalEntryId = cmd.journalEntryId,
          rejectedBy = cmd.rejectedBy,
          rejectedAt = Instant.now(),
          reason = cmd.reason
        )

        Effect
          .persist(event)
          .thenRun { _ =>
            cmd.replyTo ! StatusReply.Success(event)
          }
      }

    // 転記
    case (JournalEntryState(entry), cmd: PostJournalEntry) =>
      if (!entry.canBePosted) {
        Effect.none.thenRun { _ =>
          cmd.replyTo ! StatusReply.Error(s"転記できません。現在のステータス: ${entry.status}")
        }
      } else {
        // 各仕訳明細に対して元帳エントリIDを生成
        val ledgerEntryIds = entry.lines.map(_ => LedgerEntryId.generate())

        val event = JournalEntryPosted(
          journalEntryId = cmd.journalEntryId,
          postedBy = cmd.postedBy,
          postedAt = Instant.now(),
          ledgerEntryIds = ledgerEntryIds
        )

        Effect
          .persist(event)
          .thenRun { _ =>
            // 総勘定元帳に転記（別のActorに依頼）
            entry.lines.zip(ledgerEntryIds).foreach { case (line, ledgerEntryId) =>
              context.log.info(
                s"転記: 仕訳=${entry.id.value}, 勘定科目=${line.accountCode.value}, " +
                s"金額=${line.amount.amount}, 元帳ID=${ledgerEntryId.value}"
              )
              // GeneralLedgerActorに転記を依頼する処理は後述
            }

            cmd.replyTo ! StatusReply.Success(event)
          }
      }

    // 取消
    case (JournalEntryState(entry), cmd: CancelJournalEntry) =>
      if (!entry.canBeCancelled) {
        Effect.none.thenRun { _ =>
          cmd.replyTo ! StatusReply.Error(s"取消できません。現在のステータス: ${entry.status}")
        }
      } else {
        // 取消仕訳のIDを生成
        val reversalEntryId = JournalEntryId.generate()

        val event = JournalEntryCancelled(
          journalEntryId = cmd.journalEntryId,
          reversalEntryId = reversalEntryId,
          reason = cmd.reason,
          cancelledBy = cmd.cancelledBy,
          cancelledAt = Instant.now()
        )

        Effect
          .persist(event)
          .thenRun { _ =>
            // 取消仕訳を自動生成（逆仕訳）
            context.log.info(
              s"取消仕訳を生成: 元仕訳=${entry.id.value}, 取消仕訳=${reversalEntryId.value}"
            )
            // 取消仕訳の生成処理は後述

            cmd.replyTo ! StatusReply.Success(event)
          }
      }

    case (EmptyState, _) =>
      Effect.none  // 未作成の仕訳に対する操作は無視
  }

  // Event Handler
  private def eventHandler: (State, JournalEntryEvent) => State = {

    case (EmptyState, event: JournalEntryCreated) =>
      val lines = event.lines.zipWithIndex.map { case (lineData, index) =>
        JournalEntryLine(
          id = JournalEntryLineId.generate(),
          lineNumber = index + 1,
          accountCode = lineData.accountCode,
          debitCredit = lineData.debitCredit,
          amount = lineData.amount,
          taxInfo = lineData.taxInfo,
          auxiliaryAccount = lineData.auxiliaryAccount,
          description = lineData.description
        )
      }

      val entry = JournalEntry(
        id = event.journalEntryId,
        entryNumber = event.entryNumber,
        transactionDate = event.transactionDate,
        entryDate = event.entryDate,
        fiscalPeriod = event.fiscalPeriod,
        voucherType = event.voucherType,
        voucherNumber = event.voucherNumber,
        lines = lines,
        description = event.description,
        referenceInfo = event.referenceInfo,
        status = if (event.totalAmount.amount >= Money(1000000).amount)
                   JournalEntryStatus.Draft
                 else
                   JournalEntryStatus.Approved,  // 100万円未満は自動承認
        approvalInfo = None,
        postingInfo = None,
        reversalInfo = None,
        version = Version(1)
      )

      JournalEntryState(entry)

    case (JournalEntryState(entry), _: ApprovalRequested) =>
      JournalEntryState(
        entry.copy(
          status = JournalEntryStatus.PendingApproval,
          version = entry.version.increment
        )
      )

    case (JournalEntryState(entry), event: JournalEntryApproved) =>
      JournalEntryState(
        entry.copy(
          status = JournalEntryStatus.Approved,
          approvalInfo = Some(ApprovalInfo(
            approver = event.approver,
            approvedAt = event.approvedAt,
            comment = event.comment
          )),
          version = entry.version.increment
        )
      )

    case (JournalEntryState(entry), event: JournalEntryRejected) =>
      JournalEntryState(
        entry.copy(
          status = JournalEntryStatus.Draft,  // 却下されたら下書きに戻す
          version = entry.version.increment
        )
      )

    case (JournalEntryState(entry), event: JournalEntryPosted) =>
      JournalEntryState(
        entry.copy(
          status = JournalEntryStatus.Posted,
          postingInfo = Some(PostingInfo(
            postedBy = event.postedBy,
            postedAt = event.postedAt,
            ledgerEntryIds = event.ledgerEntryIds
          )),
          version = entry.version.increment
        )
      )

    case (JournalEntryState(entry), event: JournalEntryCancelled) =>
      JournalEntryState(
        entry.copy(
          status = JournalEntryStatus.Cancelled,
          reversalInfo = Some(ReversalInfo(
            reversalEntryId = event.reversalEntryId,
            reason = event.reason,
            cancelledBy = event.cancelledBy,
            cancelledAt = event.cancelledAt
          )),
          version = entry.version.increment
        )
      )

    case (state, _) => state
  }

  // ビジネスルールの検証
  private def validateJournalEntry(
    cmd: CreateJournalEntry,
    chartOfAccountsService: ChartOfAccountsService
  ): Either[ValidationError, Unit] = {
    for {
      _ <- validateBalance(cmd.lines)
      _ <- validateLines(cmd.lines)
      _ <- validateFiscalPeriod(cmd.fiscalPeriod)
      _ <- validateAccounts(cmd.lines, chartOfAccountsService)
    } yield ()
  }

  // 貸借一致の検証
  private def validateBalance(lines: List[JournalEntryLineData]): Either[ValidationError, Unit] = {
    val debitTotal = lines
      .filter(_.debitCredit == DebitCredit.Debit)
      .map(_.amount.amount)
      .sum

    val creditTotal = lines
      .filter(_.debitCredit == DebitCredit.Credit)
      .map(_.amount.amount)
      .sum

    if (debitTotal == creditTotal) Right(())
    else Left(ValidationError(
      s"仕訳の貸借が一致しません: 借方=${debitTotal}, 貸方=${creditTotal}"
    ))
  }

  // 明細行数の検証
  private def validateLines(lines: List[JournalEntryLineData]): Either[ValidationError, Unit] = {
    if (lines.size >= 2) Right(())
    else Left(ValidationError("仕訳明細は2行以上必要です"))
  }

  // 会計期間の検証
  private def validateFiscalPeriod(fiscalPeriod: FiscalPeriod): Either[ValidationError, Unit] = {
    if (fiscalPeriod.isClosed) {
      Left(ValidationError(s"会計期間が締め後のため、仕訳を登録できません: ${fiscalPeriod}"))
    } else Right(())
  }

  // 勘定科目の存在確認
  private def validateAccounts(
    lines: List[JournalEntryLineData],
    chartOfAccountsService: ChartOfAccountsService
  ): Either[ValidationError, Unit] = {
    val invalidAccounts = lines
      .map(_.accountCode)
      .distinct
      .filterNot(code => chartOfAccountsService.exists(code))

    if (invalidAccounts.isEmpty) Right(())
    else Left(ValidationError(
      s"存在しない勘定科目が含まれています: ${invalidAccounts.map(_.value).mkString(", ")}"
    ))
  }

  // 合計金額の計算
  private def calculateTotalAmount(lines: List[JournalEntryLineData]): Money = {
    Money(lines.filter(_.debitCredit == DebitCredit.Debit).map(_.amount.amount).sum)
  }
}

// 勘定科目サービス（簡易版）
trait ChartOfAccountsService {
  def exists(accountCode: AccountCode): Boolean
  def getAccount(accountCode: AccountCode): Option[AccountSubject]
}
```

### 5.1.3 単体テスト

```scala
class JournalEntryActorSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike {

  "JournalEntryActor" should {

    "仕訳を作成できる" in {
      val journalEntryId = JournalEntryId.generate()
      val entryNumberGenerator = () => EntryNumber(1001)
      val chartOfAccountsService = new TestChartOfAccountsService()

      val actor = spawn(JournalEntryActor(
        journalEntryId,
        entryNumberGenerator,
        chartOfAccountsService
      ))

      val probe = createTestProbe[StatusReply[JournalEntryCreated]]()

      actor ! CreateJournalEntry(
        journalEntryId = journalEntryId,
        transactionDate = LocalDate.of(2024, 4, 1),
        entryDate = LocalDate.of(2024, 4, 1),
        fiscalPeriod = FiscalPeriod(2024, 4),
        voucherType = VoucherType.Sales,
        voucherNumber = Some(VoucherNumber("S-20240401-001")),
        lines = List(
          // 借方: 売掛金
          JournalEntryLineData(
            accountCode = AccountCode("1130"),
            debitCredit = DebitCredit.Debit,
            amount = Money(1100000),
            taxInfo = None,
            auxiliaryAccount = Some(AuxiliaryAccount(
              AuxiliaryAccountType.Customer,
              "CUST-001",
              "顧客A社"
            )),
            description = Some("売掛金")
          ),
          // 貸方: 売上高
          JournalEntryLineData(
            accountCode = AccountCode("4120"),
            debitCredit = DebitCredit.Credit,
            amount = Money(1000000),
            taxInfo = Some(TaxInfo(
              TaxCategory.Standard10,
              TaxRate(0.10),
              Money(100000)
            )),
            auxiliaryAccount = None,
            description = Some("売上高")
          ),
          // 貸方: 仮受消費税
          JournalEntryLineData(
            accountCode = AccountCode("2190"),
            debitCredit = DebitCredit.Credit,
            amount = Money(100000),
            taxInfo = Some(TaxInfo(
              TaxCategory.Standard10,
              TaxRate(0.10),
              Money(100000)
            )),
            auxiliaryAccount = None,
            description = Some("仮受消費税")
          )
        ),
        description = "売上計上",
        referenceInfo = Some(ReferenceInfo(
          referenceType = "Order",
          referenceId = "ORD-001",
          sourceEventId = Some("evt-001")
        )),
        createdBy = UserId("user-001"),
        replyTo = probe.ref
      )

      val reply = probe.receiveMessage()
      reply.isSuccess shouldBe true

      val event = reply.getValue
      event.journalEntryId shouldBe journalEntryId
      event.entryNumber shouldBe EntryNumber(1001)
      event.totalAmount shouldBe Money(1100000)
      event.lines.size shouldBe 3
    }

    "貸借が一致しない仕訳は作成できない" in {
      val journalEntryId = JournalEntryId.generate()
      val entryNumberGenerator = () => EntryNumber(1002)
      val chartOfAccountsService = new TestChartOfAccountsService()

      val actor = spawn(JournalEntryActor(
        journalEntryId,
        entryNumberGenerator,
        chartOfAccountsService
      ))

      val probe = createTestProbe[StatusReply[JournalEntryCreated]]()

      actor ! CreateJournalEntry(
        journalEntryId = journalEntryId,
        transactionDate = LocalDate.of(2024, 4, 1),
        entryDate = LocalDate.of(2024, 4, 1),
        fiscalPeriod = FiscalPeriod(2024, 4),
        voucherType = VoucherType.Sales,
        voucherNumber = None,
        lines = List(
          JournalEntryLineData(
            accountCode = AccountCode("1130"),
            debitCredit = DebitCredit.Debit,
            amount = Money(1000000),
            taxInfo = None,
            auxiliaryAccount = None,
            description = None
          ),
          JournalEntryLineData(
            accountCode = AccountCode("4120"),
            debitCredit = DebitCredit.Credit,
            amount = Money(900000),  // 貸借不一致
            taxInfo = None,
            auxiliaryAccount = None,
            description = None
          )
        ),
        description = "テスト",
        referenceInfo = None,
        createdBy = UserId("user-001"),
        replyTo = probe.ref
      )

      val reply = probe.receiveMessage()
      reply.isError shouldBe true
      reply.getError.getMessage should include("貸借が一致しません")
    }

    "100万円未満の仕訳は自動承認される" in {
      val journalEntryId = JournalEntryId.generate()
      val entryNumberGenerator = () => EntryNumber(1003)
      val chartOfAccountsService = new TestChartOfAccountsService()

      val actor = spawn(JournalEntryActor(
        journalEntryId,
        entryNumberGenerator,
        chartOfAccountsService
      ))

      val createProbe = createTestProbe[StatusReply[JournalEntryCreated]]()

      actor ! CreateJournalEntry(
        journalEntryId = journalEntryId,
        transactionDate = LocalDate.of(2024, 4, 1),
        entryDate = LocalDate.of(2024, 4, 1),
        fiscalPeriod = FiscalPeriod(2024, 4),
        voucherType = VoucherType.Transfer,
        voucherNumber = None,
        lines = List(
          JournalEntryLineData(
            accountCode = AccountCode("1111"),
            debitCredit = DebitCredit.Debit,
            amount = Money(500000),
            taxInfo = None,
            auxiliaryAccount = None,
            description = None
          ),
          JournalEntryLineData(
            accountCode = AccountCode("1112"),
            debitCredit = DebitCredit.Credit,
            amount = Money(500000),
            taxInfo = None,
            auxiliaryAccount = None,
            description = None
          )
        ),
        description = "振替",
        referenceInfo = None,
        createdBy = UserId("user-001"),
        replyTo = createProbe.ref
      )

      val createReply = createProbe.receiveMessage()
      createReply.isSuccess shouldBe true

      // 転記可能（自動承認されているため）
      val postProbe = createTestProbe[StatusReply[JournalEntryPosted]]()
      actor ! PostJournalEntry(
        journalEntryId = journalEntryId,
        postedBy = UserId("user-001"),
        replyTo = postProbe.ref
      )

      val postReply = postProbe.receiveMessage()
      postReply.isSuccess shouldBe true
    }

    "承認ワークフローが正しく機能する" in {
      val journalEntryId = JournalEntryId.generate()
      val entryNumberGenerator = () => EntryNumber(1004)
      val chartOfAccountsService = new TestChartOfAccountsService()

      val actor = spawn(JournalEntryActor(
        journalEntryId,
        entryNumberGenerator,
        chartOfAccountsService
      ))

      // 1. 仕訳作成（100万円以上）
      val createProbe = createTestProbe[StatusReply[JournalEntryCreated]]()
      actor ! CreateJournalEntry(
        journalEntryId = journalEntryId,
        transactionDate = LocalDate.of(2024, 4, 1),
        entryDate = LocalDate.of(2024, 4, 1),
        fiscalPeriod = FiscalPeriod(2024, 4),
        voucherType = VoucherType.Sales,
        voucherNumber = None,
        lines = List(
          JournalEntryLineData(
            accountCode = AccountCode("1130"),
            debitCredit = DebitCredit.Debit,
            amount = Money(5000000),
            taxInfo = None,
            auxiliaryAccount = None,
            description = None
          ),
          JournalEntryLineData(
            accountCode = AccountCode("4120"),
            debitCredit = DebitCredit.Credit,
            amount = Money(5000000),
            taxInfo = None,
            auxiliaryAccount = None,
            description = None
          )
        ),
        description = "大口売上",
        referenceInfo = None,
        createdBy = UserId("user-001"),
        replyTo = createProbe.ref
      )
      createProbe.receiveMessage().isSuccess shouldBe true

      // 2. 承認申請
      val requestProbe = createTestProbe[StatusReply[ApprovalRequested]]()
      actor ! RequestApproval(
        journalEntryId = journalEntryId,
        requestedBy = UserId("user-001"),
        replyTo = requestProbe.ref
      )
      requestProbe.receiveMessage().isSuccess shouldBe true

      // 3. 承認
      val approveProbe = createTestProbe[StatusReply[JournalEntryApproved]]()
      actor ! ApproveJournalEntry(
        journalEntryId = journalEntryId,
        approver = UserId("manager-001"),
        comment = Some("承認します"),
        replyTo = approveProbe.ref
      )
      approveProbe.receiveMessage().isSuccess shouldBe true

      // 4. 転記
      val postProbe = createTestProbe[StatusReply[JournalEntryPosted]]()
      actor ! PostJournalEntry(
        journalEntryId = journalEntryId,
        postedBy = UserId("user-001"),
        replyTo = postProbe.ref
      )
      postProbe.receiveMessage().isSuccess shouldBe true
    }
  }
}

// テスト用勘定科目サービス
class TestChartOfAccountsService extends ChartOfAccountsService {
  private val validAccounts = Set(
    "1111", "1112", "1130", "2190", "4120"
  )

  override def exists(accountCode: AccountCode): Boolean = {
    validAccounts.contains(accountCode.value)
  }

  override def getAccount(accountCode: AccountCode): Option[AccountSubject] = None
}
```

## 5.2 GeneralLedger集約の実装

総勘定元帳集約は、勘定科目ごとの取引履歴と残高を管理します。

### 5.2.1 コマンドとイベントの定義

```scala
// GeneralLedger コマンド
sealed trait GeneralLedgerCommand

// 元帳転記
final case class PostLedgerEntry(
  ledgerEntryId: LedgerEntryId,
  accountCode: AccountCode,
  fiscalPeriod: FiscalPeriod,
  entryDate: LocalDate,
  journalEntryId: JournalEntryId,
  journalEntryLineId: JournalEntryLineId,
  debitCredit: DebitCredit,
  amount: Money,
  auxiliaryAccount: Option[AuxiliaryAccount],
  description: String,
  postedBy: UserId,
  replyTo: ActorRef[StatusReply[LedgerEntryPosted]]
) extends GeneralLedgerCommand

// GeneralLedger イベント
sealed trait GeneralLedgerEvent

// 元帳エントリ転記イベント
final case class LedgerEntryPosted(
  ledgerEntryId: LedgerEntryId,
  accountCode: AccountCode,
  fiscalPeriod: FiscalPeriod,
  entryDate: LocalDate,
  journalEntryId: JournalEntryId,
  journalEntryLineId: JournalEntryLineId,
  debitCredit: DebitCredit,
  amount: Money,
  balance: Money,
  auxiliaryAccount: Option[AuxiliaryAccount],
  description: String,
  postedBy: UserId,
  postedAt: Instant
) extends GeneralLedgerEvent

// 残高更新イベント
final case class LedgerBalanceUpdated(
  accountCode: AccountCode,
  fiscalPeriod: FiscalPeriod,
  previousBalance: Money,
  newBalance: Money,
  updatedAt: Instant
) extends GeneralLedgerEvent
```

### 5.2.2 GeneralLedgerActor の実装

```scala
object GeneralLedgerActor {

  // State
  sealed trait State
  case object EmptyState extends State
  final case class GeneralLedgerState(ledger: GeneralLedger) extends State

  def apply(
    accountCode: AccountCode,
    fiscalPeriod: FiscalPeriod,
    openingBalance: Money
  ): Behavior[GeneralLedgerCommand] = {
    EventSourcedBehavior[GeneralLedgerCommand, GeneralLedgerEvent, State](
      persistenceId = PersistenceId.ofUniqueId(
        s"GeneralLedger-${accountCode.value}-${fiscalPeriod.fiscalYear}-${fiscalPeriod.fiscalMonth}"
      ),
      emptyState = EmptyState,
      commandHandler = commandHandler,
      eventHandler = eventHandler
    ).withRetention(RetentionCriteria.snapshotEvery(numberOfEvents = 100, keepNSnapshots = 2))
  }

  // Command Handler
  private def commandHandler: (State, GeneralLedgerCommand) => Effect[GeneralLedgerEvent, State] = {

    // 初回の転記（元帳作成）
    case (EmptyState, cmd: PostLedgerEntry) =>
      val balance = calculateBalance(
        openingBalance = Money(0),  // 初期残高は0（実際は別途設定）
        debitCredit = cmd.debitCredit,
        amount = cmd.amount
      )

      val event = LedgerEntryPosted(
        ledgerEntryId = cmd.ledgerEntryId,
        accountCode = cmd.accountCode,
        fiscalPeriod = cmd.fiscalPeriod,
        entryDate = cmd.entryDate,
        journalEntryId = cmd.journalEntryId,
        journalEntryLineId = cmd.journalEntryLineId,
        debitCredit = cmd.debitCredit,
        amount = cmd.amount,
        balance = balance,
        auxiliaryAccount = cmd.auxiliaryAccount,
        description = cmd.description,
        postedBy = cmd.postedBy,
        postedAt = Instant.now()
      )

      Effect
        .persist(event)
        .thenRun { _ =>
          cmd.replyTo ! StatusReply.Success(event)
        }

    // 既存元帳への転記
    case (GeneralLedgerState(ledger), cmd: PostLedgerEntry) =>
      val newBalance = calculateBalance(
        openingBalance = ledger.currentBalance,
        debitCredit = cmd.debitCredit,
        amount = cmd.amount
      )

      val event = LedgerEntryPosted(
        ledgerEntryId = cmd.ledgerEntryId,
        accountCode = cmd.accountCode,
        fiscalPeriod = cmd.fiscalPeriod,
        entryDate = cmd.entryDate,
        journalEntryId = cmd.journalEntryId,
        journalEntryLineId = cmd.journalEntryLineId,
        debitCredit = cmd.debitCredit,
        amount = cmd.amount,
        balance = newBalance,
        auxiliaryAccount = cmd.auxiliaryAccount,
        description = cmd.description,
        postedBy = cmd.postedBy,
        postedAt = Instant.now()
      )

      Effect
        .persist(event)
        .thenRun { _ =>
          cmd.replyTo ! StatusReply.Success(event)
        }
  }

  // Event Handler
  private def eventHandler: (State, GeneralLedgerEvent) => State = {

    case (EmptyState, event: LedgerEntryPosted) =>
      val entry = LedgerEntry(
        id = event.ledgerEntryId,
        entryDate = event.entryDate,
        journalEntryId = event.journalEntryId,
        journalEntryLineId = event.journalEntryLineId,
        debitCredit = event.debitCredit,
        amount = event.amount,
        balance = event.balance,
        auxiliaryAccount = event.auxiliaryAccount,
        description = event.description,
        postedBy = event.postedBy,
        postedAt = event.postedAt
      )

      val ledger = GeneralLedger(
        id = GeneralLedgerId.generate(),
        accountCode = event.accountCode,
        fiscalPeriod = event.fiscalPeriod,
        entries = List(entry),
        openingBalance = Money(0),
        currentBalance = event.balance,
        version = Version(1)
      )

      GeneralLedgerState(ledger)

    case (GeneralLedgerState(ledger), event: LedgerEntryPosted) =>
      val entry = LedgerEntry(
        id = event.ledgerEntryId,
        entryDate = event.entryDate,
        journalEntryId = event.journalEntryId,
        journalEntryLineId = event.journalEntryLineId,
        debitCredit = event.debitCredit,
        amount = event.amount,
        balance = event.balance,
        auxiliaryAccount = event.auxiliaryAccount,
        description = event.description,
        postedBy = event.postedBy,
        postedAt = event.postedAt
      )

      GeneralLedgerState(
        ledger.copy(
          entries = ledger.entries :+ entry,
          currentBalance = event.balance,
          version = ledger.version.increment
        )
      )

    case (state, _) => state
  }

  // 残高計算
  private def calculateBalance(
    openingBalance: Money,
    debitCredit: DebitCredit,
    amount: Money
  ): Money = {
    debitCredit match {
      case DebitCredit.Debit =>
        Money(openingBalance.amount + amount.amount)
      case DebitCredit.Credit =>
        Money(openingBalance.amount - amount.amount)
    }
  }
}
```

## 5.3 FinancialStatement集約の実装

財務諸表集約は、試算表、損益計算書、貸借対照表、キャッシュフロー計算書を生成します。

### 5.3.1 コマンドとイベントの定義

```scala
// FinancialStatement コマンド
sealed trait FinancialStatementCommand

// 試算表作成
final case class GenerateTrialBalance(
  trialBalanceId: TrialBalanceId,
  fiscalPeriod: FiscalPeriod,
  generatedBy: UserId,
  replyTo: ActorRef[StatusReply[TrialBalanceGenerated]]
) extends FinancialStatementCommand

// 損益計算書作成
final case class GenerateIncomeStatement(
  statementId: IncomeStatementId,
  fiscalPeriod: FiscalPeriod,
  periodType: PeriodType,
  generatedBy: UserId,
  replyTo: ActorRef[StatusReply[IncomeStatementGenerated]]
) extends FinancialStatementCommand

// FinancialStatement イベント
sealed trait FinancialStatementEvent

// 試算表生成イベント
final case class TrialBalanceGenerated(
  trialBalanceId: TrialBalanceId,
  fiscalPeriod: FiscalPeriod,
  balances: List[AccountBalanceData],
  totalDebit: Money,
  totalCredit: Money,
  generatedBy: UserId,
  generatedAt: Instant
) extends FinancialStatementEvent

// 損益計算書生成イベント
final case class IncomeStatementGenerated(
  statementId: IncomeStatementId,
  fiscalPeriod: FiscalPeriod,
  periodType: PeriodType,
  asOfDate: LocalDate,
  revenue: Money,
  costOfGoodsSold: Money,
  grossProfit: Money,
  operatingExpenses: Money,
  operatingIncome: Money,
  nonOperatingIncome: Money,
  nonOperatingExpenses: Money,
  ordinaryIncome: Money,
  extraordinaryIncome: Money,
  extraordinaryLoss: Money,
  incomeBeforeTax: Money,
  incomeTax: Money,
  netIncome: Money,
  generatedBy: UserId,
  generatedAt: Instant
) extends FinancialStatementEvent

// 勘定科目別残高データ
final case class AccountBalanceData(
  accountCode: AccountCode,
  accountName: String,
  openingBalance: Money,
  debitTotal: Money,
  creditTotal: Money,
  closingBalance: Money
)
```

### 5.3.2 FinancialStatementActor の実装

```scala
object FinancialStatementActor {

  // State（簡易版）
  sealed trait State
  case object EmptyState extends State
  final case class StatementState(
    trialBalances: List[TrialBalance],
    incomeStatements: List[IncomeStatement]
  ) extends State

  def apply(
    fiscalPeriod: FiscalPeriod,
    generalLedgerQueryService: GeneralLedgerQueryService
  ): Behavior[FinancialStatementCommand] = {
    EventSourcedBehavior[FinancialStatementCommand, FinancialStatementEvent, State](
      persistenceId = PersistenceId.ofUniqueId(
        s"FinancialStatement-${fiscalPeriod.fiscalYear}-${fiscalPeriod.fiscalMonth}"
      ),
      emptyState = EmptyState,
      commandHandler = commandHandler(generalLedgerQueryService),
      eventHandler = eventHandler
    )
  }

  // Command Handler
  private def commandHandler(
    generalLedgerQueryService: GeneralLedgerQueryService
  ): (State, FinancialStatementCommand) => Effect[FinancialStatementEvent, State] = {

    case (_, cmd: GenerateTrialBalance) =>
      // 全勘定科目の残高を取得
      val balances = generalLedgerQueryService.getAllAccountBalances(cmd.fiscalPeriod)

      val totalDebit = Money(balances.map(_.debitTotal.amount).sum)
      val totalCredit = Money(balances.map(_.creditTotal.amount).sum)

      // 貸借一致チェック
      if (totalDebit.amount != totalCredit.amount) {
        Effect.none.thenRun { _ =>
          cmd.replyTo ! StatusReply.Error(
            s"試算表の貸借が一致しません: 借方=${totalDebit.amount}, 貸方=${totalCredit.amount}"
          )
        }
      } else {
        val event = TrialBalanceGenerated(
          trialBalanceId = cmd.trialBalanceId,
          fiscalPeriod = cmd.fiscalPeriod,
          balances = balances,
          totalDebit = totalDebit,
          totalCredit = totalCredit,
          generatedBy = cmd.generatedBy,
          generatedAt = Instant.now()
        )

        Effect
          .persist(event)
          .thenRun { _ =>
            cmd.replyTo ! StatusReply.Success(event)
          }
      }

    case (_, cmd: GenerateIncomeStatement) =>
      // 収益・費用科目の集計
      val revenue = generalLedgerQueryService.getAccountTotal(
        AccountCode("4120"), cmd.fiscalPeriod
      )
      val costOfGoodsSold = generalLedgerQueryService.getAccountTotal(
        AccountCode("5120"), cmd.fiscalPeriod
      )
      val operatingExpenses = generalLedgerQueryService.getAccountCategoryTotal(
        AccountCategory.Expense, cmd.fiscalPeriod
      ).filter(_.accountCode.value.startsWith("52"))  // 販管費
        .map(_.closingBalance.amount).sum

      val grossProfit = Money(revenue.amount - costOfGoodsSold.amount)
      val operatingIncome = Money(grossProfit.amount - operatingExpenses)

      val event = IncomeStatementGenerated(
        statementId = cmd.statementId,
        fiscalPeriod = cmd.fiscalPeriod,
        periodType = cmd.periodType,
        asOfDate = cmd.fiscalPeriod.endDate,
        revenue = revenue,
        costOfGoodsSold = costOfGoodsSold,
        grossProfit = grossProfit,
        operatingExpenses = Money(operatingExpenses),
        operatingIncome = operatingIncome,
        nonOperatingIncome = Money(0),
        nonOperatingExpenses = Money(0),
        ordinaryIncome = operatingIncome,
        extraordinaryIncome = Money(0),
        extraordinaryLoss = Money(0),
        incomeBeforeTax = operatingIncome,
        incomeTax = Money(0),
        netIncome = operatingIncome,
        generatedBy = cmd.generatedBy,
        generatedAt = Instant.now()
      )

      Effect
        .persist(event)
        .thenRun { _ =>
          cmd.replyTo ! StatusReply.Success(event)
        }
  }

  // Event Handler（省略）
  private def eventHandler: (State, FinancialStatementEvent) => State = {
    case (state, _) => state  // 簡易版
  }
}

// 総勘定元帳クエリサービス
trait GeneralLedgerQueryService {
  def getAllAccountBalances(fiscalPeriod: FiscalPeriod): List[AccountBalanceData]
  def getAccountTotal(accountCode: AccountCode, fiscalPeriod: FiscalPeriod): Money
  def getAccountCategoryTotal(category: AccountCategory, fiscalPeriod: FiscalPeriod): List[AccountBalanceData]
}
```

## まとめ

本章では、会計システムの3つの主要な集約をPekko Persistenceで実装しました。

**実装した集約**:
1. **JournalEntryActor**: 仕訳の作成・承認・転記・取消
2. **GeneralLedgerActor**: 総勘定元帳への転記と残高管理
3. **FinancialStatementActor**: 試算表・損益計算書の生成

**実装したビジネスルール**:
- 仕訳の貸借一致検証
- 金額に応じた承認ワークフロー（100万円以上は承認必要）
- 会計期間の締め後は仕訳登録不可
- 勘定科目の存在確認
- 総勘定元帳の残高計算（借方は加算、貸方は減算）
- 試算表の貸借一致チェック

**イベントソーシングの利点**:
- 全ての仕訳変更履歴を完全に追跡可能（監査証跡）
- 過去の任意時点の状態を復元可能
- イベントから財務諸表を再計算可能

次章では、ビジネスイベント（受注確定、入荷完了、入金など）から仕訳を自動生成するイベント駆動会計を実装します。
