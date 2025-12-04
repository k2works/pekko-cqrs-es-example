package io.github.j5ik2o.pcqrses.command.useCase.inventory

import io.github.j5ik2o.pcqrses.command.domain.inventory.{
  CategoryCode,
  ProductCode,
  ProductId,
  ProductName,
  StorageCondition
}
import io.github.j5ik2o.pcqrses.command.interfaceAdapter.contract.inventory.ProductProtocol
import org.apache.pekko.actor.typed.{ActorRef, Scheduler}
import org.apache.pekko.util.Timeout
import zio.IO

import scala.concurrent.ExecutionContext

/**
  * Productユースケースのインターフェース
  *
  * 商品管理のビジネスロジックを定義する
  */
trait ProductUseCase {

  /**
    * 商品を作成する
    *
    * @param productCode 商品コード
    * @param name 商品名
    * @param categoryCode カテゴリーコード
    * @param storageCondition 保管条件
    * @return 作成された商品のID
    */
  def createProduct(
      productCode: ProductCode,
      name: ProductName,
      categoryCode: CategoryCode,
      storageCondition: StorageCondition
  ): IO[ProductUseCaseError, ProductId]

  /**
    * 商品情報を更新する
    *
    * @param productId 商品ID
    * @param newName 新しい商品名
    * @param newCategoryCode 新しいカテゴリーコード
    * @param newStorageCondition 新しい保管条件
    * @return 更新された商品のID
    */
  def updateProduct(
      productId: ProductId,
      newName: ProductName,
      newCategoryCode: CategoryCode,
      newStorageCondition: StorageCondition
  ): IO[ProductUseCaseError, ProductId]

  /**
    * 商品を廃番にする
    *
    * @param productId 商品ID
    * @return 廃番にした商品のID
    */
  def obsoleteProduct(
      productId: ProductId
  ): IO[ProductUseCaseError, ProductId]
}

object ProductUseCase {

  /**
    * ProductUseCaseのインスタンスを作成する
    *
    * @param productAggregateRef 商品アグリゲートのActorRef
    * @param timeout アクタータイムアウト
    * @param scheduler スケジューラー
    * @param ec 実行コンテキスト
    * @return ProductUseCaseのインスタンス
    */
  def apply(
      productAggregateRef: ActorRef[ProductProtocol.Command]
  )(implicit
      timeout: Timeout,
      scheduler: Scheduler,
      ec: ExecutionContext
  ): ProductUseCase =
    new ProductUseCaseImpl(productAggregateRef)
}
