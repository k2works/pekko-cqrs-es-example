package io.github.j5ik2o.pcqrses.command.interfaceAdapter.contract.inventory

import io.github.j5ik2o.pcqrses.command.domain.inventory.*
import org.apache.pekko.actor.typed.ActorRef

object CustomerProtocol {
  sealed trait Command {
    def id: CustomerId
  }

  /** 取引先作成コマンド */
  final case class CreateCustomer(
      id: CustomerId,
      customerCode: CustomerCode,
      name: CustomerName,
      customerType: CustomerType,
      replyTo: ActorRef[CreateCustomerReply]
  ) extends Command

  /** 取引先情報更新コマンド */
  final case class UpdateCustomer(
      id: CustomerId,
      newName: CustomerName,
      newCustomerType: CustomerType,
      replyTo: ActorRef[UpdateCustomerReply]
  ) extends Command

  /** 取引先無効化コマンド */
  final case class DeactivateCustomer(
      id: CustomerId,
      replyTo: ActorRef[DeactivateCustomerReply]
  ) extends Command

  /** 取引先再有効化コマンド */
  final case class ReactivateCustomer(
      id: CustomerId,
      replyTo: ActorRef[ReactivateCustomerReply]
  ) extends Command

  /** 取引先取得コマンド */
  final case class GetCustomer(
      id: CustomerId,
      replyTo: ActorRef[GetCustomerReply]
  ) extends Command

  // --- 応答 ---

  sealed trait CreateCustomerReply
  final case class CreateCustomerSucceeded(id: CustomerId) extends CreateCustomerReply

  sealed trait UpdateCustomerReply
  final case class UpdateCustomerSucceeded(id: CustomerId) extends UpdateCustomerReply
  final case class UpdateCustomerFailed(id: CustomerId, reason: UpdateCustomerError)
      extends UpdateCustomerReply

  sealed trait DeactivateCustomerReply
  final case class DeactivateCustomerSucceeded(id: CustomerId) extends DeactivateCustomerReply
  final case class DeactivateCustomerFailed(id: CustomerId, reason: DeactivateCustomerError)
      extends DeactivateCustomerReply

  sealed trait ReactivateCustomerReply
  final case class ReactivateCustomerSucceeded(id: CustomerId) extends ReactivateCustomerReply
  final case class ReactivateCustomerFailed(id: CustomerId, reason: ReactivateCustomerError)
      extends ReactivateCustomerReply

  sealed trait GetCustomerReply
  final case class GetCustomerSucceeded(value: Customer) extends GetCustomerReply
  final case class GetCustomerNotFoundFailed(id: CustomerId) extends GetCustomerReply

  final case class Stop(id: CustomerId) extends Command
}
