package io.github.j5ik2o.pcqrses.command.useCase.inventory

import io.github.j5ik2o.pcqrses.command.domain.inventory.{
  StorageCondition,
  WarehouseId,
  WarehouseZoneId,
  ZoneCapacity,
  ZoneCode,
  ZoneName
}
import io.github.j5ik2o.pcqrses.command.interfaceAdapter.contract.inventory.WarehouseZoneProtocol
import org.apache.pekko.actor.typed.{ActorRef, Scheduler}
import org.apache.pekko.util.Timeout
import zio.IO

import scala.concurrent.ExecutionContext

/**
  * WarehouseZoneユースケースのインターフェース
  *
  * 倉庫ゾーン管理のビジネスロジックを定義する
  */
trait WarehouseZoneUseCase {

  /**
    * 倉庫ゾーンを作成する
    *
    * @param warehouseId 倉庫ID
    * @param zoneCode ゾーンコード
    * @param name ゾーン名
    * @param zoneType ゾーンタイプ（保管条件）
    * @param capacity ゾーン容量
    * @return 作成された倉庫ゾーンのID
    */
  def createWarehouseZone(
      warehouseId: WarehouseId,
      zoneCode: ZoneCode,
      name: ZoneName,
      zoneType: StorageCondition,
      capacity: ZoneCapacity
  ): IO[WarehouseZoneUseCaseError, WarehouseZoneId]

  /**
    * 倉庫ゾーン情報を更新する
    *
    * @param warehouseZoneId 倉庫ゾーンID
    * @param newName 新しいゾーン名
    * @param newCapacity 新しいゾーン容量
    * @return 更新された倉庫ゾーンのID
    */
  def updateWarehouseZone(
      warehouseZoneId: WarehouseZoneId,
      newName: ZoneName,
      newCapacity: ZoneCapacity
  ): IO[WarehouseZoneUseCaseError, WarehouseZoneId]

  /**
    * 倉庫ゾーンを無効化する
    *
    * @param warehouseZoneId 倉庫ゾーンID
    * @return 無効化した倉庫ゾーンのID
    */
  def deactivateWarehouseZone(
      warehouseZoneId: WarehouseZoneId
  ): IO[WarehouseZoneUseCaseError, WarehouseZoneId]

  /**
    * 倉庫ゾーンを再有効化する
    *
    * @param warehouseZoneId 倉庫ゾーンID
    * @return 再有効化した倉庫ゾーンのID
    */
  def reactivateWarehouseZone(
      warehouseZoneId: WarehouseZoneId
  ): IO[WarehouseZoneUseCaseError, WarehouseZoneId]
}

object WarehouseZoneUseCase {

  /**
    * WarehouseZoneUseCaseのインスタンスを作成する
    *
    * @param warehouseZoneAggregateRef 倉庫ゾーンアグリゲートのActorRef
    * @param timeout アクタータイムアウト
    * @param scheduler スケジューラー
    * @param ec 実行コンテキスト
    * @return WarehouseZoneUseCaseのインスタンス
    */
  def apply(
      warehouseZoneAggregateRef: ActorRef[WarehouseZoneProtocol.Command]
  )(implicit
      timeout: Timeout,
      scheduler: Scheduler,
      ec: ExecutionContext
  ): WarehouseZoneUseCase =
    new WarehouseZoneUseCaseImpl(warehouseZoneAggregateRef)
}
