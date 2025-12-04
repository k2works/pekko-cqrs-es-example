package io.github.j5ik2o.pcqrses.command.useCase.inventory

import io.github.j5ik2o.pcqrses.command.domain.inventory.{
  InventoryId,
  InventoryQuantity,
  InventoryVersion,
  ProductId,
  WarehouseZoneId
}
import io.github.j5ik2o.pcqrses.command.interfaceAdapter.contract.inventory.InventoryProtocol
import org.apache.pekko.actor.typed.{ActorRef, Scheduler}
import org.apache.pekko.util.Timeout
import zio.IO

import scala.concurrent.ExecutionContext

/**
  * Inventoryユースケースのインターフェース
  *
  * 在庫管理のビジネスロジックを定義する
  * 楽観的ロック制御により、並行アクセス時の整合性を保証する
  */
trait InventoryUseCase {

  /**
    * 在庫を作成する
    *
    * @param productId 商品ID
    * @param warehouseZoneId 倉庫ゾーンID
    * @return 作成された在庫のID
    */
  def createInventory(
      productId: ProductId,
      warehouseZoneId: WarehouseZoneId
  ): IO[InventoryUseCaseError, InventoryId]

  /**
    * 在庫を入庫する
    *
    * @param inventoryId 在庫ID
    * @param quantity 入庫数量
    * @param expectedVersion 期待するバージョン（楽観的ロック）
    * @return 新しいバージョン
    */
  def receiveInventory(
      inventoryId: InventoryId,
      quantity: InventoryQuantity,
      expectedVersion: InventoryVersion
  ): IO[InventoryUseCaseError, InventoryVersion]

  /**
    * 在庫を引当する
    *
    * @param inventoryId 在庫ID
    * @param quantity 引当数量
    * @param expectedVersion 期待するバージョン（楽観的ロック）
    * @return 新しいバージョン
    */
  def reserveInventory(
      inventoryId: InventoryId,
      quantity: InventoryQuantity,
      expectedVersion: InventoryVersion
  ): IO[InventoryUseCaseError, InventoryVersion]

  /**
    * 在庫引当を解放する
    *
    * @param inventoryId 在庫ID
    * @param quantity 解放数量
    * @param expectedVersion 期待するバージョン（楽観的ロック）
    * @return 新しいバージョン
    */
  def releaseInventory(
      inventoryId: InventoryId,
      quantity: InventoryQuantity,
      expectedVersion: InventoryVersion
  ): IO[InventoryUseCaseError, InventoryVersion]

  /**
    * 在庫を出庫する
    *
    * @param inventoryId 在庫ID
    * @param quantity 出庫数量
    * @param expectedVersion 期待するバージョン（楽観的ロック）
    * @return 新しいバージョン
    */
  def issueInventory(
      inventoryId: InventoryId,
      quantity: InventoryQuantity,
      expectedVersion: InventoryVersion
  ): IO[InventoryUseCaseError, InventoryVersion]

  /**
    * 在庫を調整する
    *
    * @param inventoryId 在庫ID
    * @param newQuantity 調整後の在庫数量
    * @param reason 調整理由
    * @param expectedVersion 期待するバージョン（楽観的ロック）
    * @return 新しいバージョン
    */
  def adjustInventory(
      inventoryId: InventoryId,
      newQuantity: InventoryQuantity,
      reason: String,
      expectedVersion: InventoryVersion
  ): IO[InventoryUseCaseError, InventoryVersion]
}

object InventoryUseCase {

  /**
    * InventoryUseCaseのインスタンスを作成する
    *
    * @param inventoryAggregateRef 在庫アグリゲートのActorRef
    * @param timeout アクタータイムアウト
    * @param scheduler スケジューラー
    * @param ec 実行コンテキスト
    * @return InventoryUseCaseのインスタンス
    */
  def apply(
      inventoryAggregateRef: ActorRef[InventoryProtocol.Command]
  )(implicit
      timeout: Timeout,
      scheduler: Scheduler,
      ec: ExecutionContext
  ): InventoryUseCase =
    new InventoryUseCaseImpl(inventoryAggregateRef)
}
