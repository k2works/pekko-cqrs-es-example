package io.github.j5ik2o.pcqrses.command.interfaceAdapter.contract.inventory

import io.github.j5ik2o.pcqrses.command.domain.inventory.*
import org.apache.pekko.actor.typed.ActorRef

object ProductProtocol {
  sealed trait Command {
    def id: ProductId
  }

  /** 商品作成コマンド */
  final case class CreateProduct(
      id: ProductId,
      productCode: ProductCode,
      name: ProductName,
      categoryCode: CategoryCode,
      storageCondition: StorageCondition,
      replyTo: ActorRef[CreateProductReply]
  ) extends Command

  /** 商品情報更新コマンド */
  final case class UpdateProduct(
      id: ProductId,
      newName: ProductName,
      newCategoryCode: CategoryCode,
      newStorageCondition: StorageCondition,
      replyTo: ActorRef[UpdateProductReply]
  ) extends Command

  /** 商品廃番コマンド */
  final case class ObsoleteProduct(
      id: ProductId,
      replyTo: ActorRef[ObsoleteProductReply]
  ) extends Command

  /** 商品取得コマンド */
  final case class GetProduct(
      id: ProductId,
      replyTo: ActorRef[GetProductReply]
  ) extends Command

  // --- 応答 ---

  sealed trait CreateProductReply
  final case class CreateProductSucceeded(id: ProductId) extends CreateProductReply

  sealed trait UpdateProductReply
  final case class UpdateProductSucceeded(id: ProductId) extends UpdateProductReply
  final case class UpdateProductFailed(id: ProductId, reason: UpdateProductError) extends UpdateProductReply

  sealed trait ObsoleteProductReply
  final case class ObsoleteProductSucceeded(id: ProductId) extends ObsoleteProductReply
  final case class ObsoleteProductFailed(id: ProductId, reason: ObsoleteProductError) extends ObsoleteProductReply

  sealed trait GetProductReply
  final case class GetProductSucceeded(value: Product) extends GetProductReply
  final case class GetProductNotFoundFailed(id: ProductId) extends GetProductReply

  final case class Stop(id: ProductId) extends Command
}
