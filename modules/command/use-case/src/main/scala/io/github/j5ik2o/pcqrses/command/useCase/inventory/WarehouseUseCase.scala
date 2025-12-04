package io.github.j5ik2o.pcqrses.command.useCase.inventory

import io.github.j5ik2o.pcqrses.command.domain.inventory.{
  WarehouseCode,
  WarehouseId,
  WarehouseLocation,
  WarehouseName
}
import io.github.j5ik2o.pcqrses.command.interfaceAdapter.contract.inventory.WarehouseProtocol
import org.apache.pekko.actor.typed.{ActorRef, Scheduler}
import org.apache.pekko.util.Timeout
import zio.IO

import scala.concurrent.ExecutionContext

/**
  * Warehouseユースケースのインターフェース
  *
  * 倉庫管理のビジネスロジックを定義する
  */
trait WarehouseUseCase {

  /**
    * 倉庫を作成する
    *
    * @param warehouseCode 倉庫コード
    * @param name 倉庫名
    * @param location 倉庫所在地
    * @return 作成された倉庫のID
    */
  def createWarehouse(
      warehouseCode: WarehouseCode,
      name: WarehouseName,
      location: WarehouseLocation
  ): IO[WarehouseUseCaseError, WarehouseId]

  /**
    * 倉庫情報を更新する
    *
    * @param warehouseId 倉庫ID
    * @param newName 新しい倉庫名
    * @param newLocation 新しい倉庫所在地
    * @return 更新された倉庫のID
    */
  def updateWarehouse(
      warehouseId: WarehouseId,
      newName: WarehouseName,
      newLocation: WarehouseLocation
  ): IO[WarehouseUseCaseError, WarehouseId]

  /**
    * 倉庫を無効化する
    *
    * @param warehouseId 倉庫ID
    * @return 無効化した倉庫のID
    */
  def deactivateWarehouse(
      warehouseId: WarehouseId
  ): IO[WarehouseUseCaseError, WarehouseId]

  /**
    * 倉庫を再有効化する
    *
    * @param warehouseId 倉庫ID
    * @return 再有効化した倉庫のID
    */
  def reactivateWarehouse(
      warehouseId: WarehouseId
  ): IO[WarehouseUseCaseError, WarehouseId]
}

object WarehouseUseCase {

  /**
    * WarehouseUseCaseのインスタンスを作成する
    *
    * @param warehouseAggregateRef 倉庫アグリゲートのActorRef
    * @param timeout アクタータイムアウト
    * @param scheduler スケジューラー
    * @param ec 実行コンテキスト
    * @return WarehouseUseCaseのインスタンス
    */
  def apply(
      warehouseAggregateRef: ActorRef[WarehouseProtocol.Command]
  )(implicit
      timeout: Timeout,
      scheduler: Scheduler,
      ec: ExecutionContext
  ): WarehouseUseCase =
    new WarehouseUseCaseImpl(warehouseAggregateRef)
}
