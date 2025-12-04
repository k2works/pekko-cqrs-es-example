package io.github.j5ik2o.pcqrses.command.useCase.inventory

import io.github.j5ik2o.pcqrses.command.domain.inventory.{
  CustomerCode,
  CustomerId,
  CustomerName,
  CustomerType
}
import io.github.j5ik2o.pcqrses.command.interfaceAdapter.contract.inventory.CustomerProtocol
import org.apache.pekko.actor.typed.{ActorRef, Scheduler}
import org.apache.pekko.util.Timeout
import zio.IO

import scala.concurrent.ExecutionContext

/**
  * Customerユースケースのインターフェース
  *
  * 取引先管理のビジネスロジックを定義する
  */
trait CustomerUseCase {

  /**
    * 取引先を作成する
    *
    * @param customerCode 取引先コード
    * @param name 取引先名
    * @param customerType 取引先タイプ
    * @return 作成された取引先のID
    */
  def createCustomer(
      customerCode: CustomerCode,
      name: CustomerName,
      customerType: CustomerType
  ): IO[CustomerUseCaseError, CustomerId]

  /**
    * 取引先情報を更新する
    *
    * @param customerId 取引先ID
    * @param newName 新しい取引先名
    * @param newCustomerType 新しい取引先タイプ
    * @return 更新された取引先のID
    */
  def updateCustomer(
      customerId: CustomerId,
      newName: CustomerName,
      newCustomerType: CustomerType
  ): IO[CustomerUseCaseError, CustomerId]

  /**
    * 取引先を無効化する
    *
    * @param customerId 取引先ID
    * @return 無効化した取引先のID
    */
  def deactivateCustomer(
      customerId: CustomerId
  ): IO[CustomerUseCaseError, CustomerId]

  /**
    * 取引先を再有効化する
    *
    * @param customerId 取引先ID
    * @return 再有効化した取引先のID
    */
  def reactivateCustomer(
      customerId: CustomerId
  ): IO[CustomerUseCaseError, CustomerId]
}

object CustomerUseCase {

  /**
    * CustomerUseCaseのインスタンスを作成する
    *
    * @param customerAggregateRef 取引先アグリゲートのActorRef
    * @param timeout アクタータイムアウト
    * @param scheduler スケジューラー
    * @param ec 実行コンテキスト
    * @return CustomerUseCaseのインスタンス
    */
  def apply(
      customerAggregateRef: ActorRef[CustomerProtocol.Command]
  )(implicit
      timeout: Timeout,
      scheduler: Scheduler,
      ec: ExecutionContext
  ): CustomerUseCase =
    new CustomerUseCaseImpl(customerAggregateRef)
}
