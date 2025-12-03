package io.github.j5ik2o.pcqrses.command.interfaceAdapter.aggregate.inventory

import io.github.j5ik2o.pcqrses.command.domain.inventory.*

enum InventoryAggregateState {
  case NotCreated(id: InventoryId)
  case Created(inventory: Inventory)

  def applyEvent(event: InventoryEvent): InventoryAggregateState = (this, event) match {
    // 入庫イベント（初回入庫で在庫作成）
    case (
          NotCreated(id),
          InventoryEvent.Received_V1(_, entityId, productId, warehouseZoneId, quantity, version, _)
        ) if id == entityId =>
      val inventory = Inventory.create(entityId, productId, warehouseZoneId)
      Created(inventory.receive(quantity, InventoryVersion.Initial) match {
        case Right((receivedInventory, _)) => receivedInventory
        case Left(error) =>
          throw new IllegalStateException(s"Failed to receive inventory: $error")
      })

    // 入庫イベント（既存在庫への入庫）
    case (Created(inventory), InventoryEvent.Received_V1(_, entityId, _, _, quantity, version, _))
        if inventory.id == entityId =>
      Created(inventory.receive(quantity, inventory.version) match {
        case Right((receivedInventory, _)) => receivedInventory
        case Left(error) =>
          throw new IllegalStateException(s"Failed to receive inventory: $error")
      })

    // 引当イベント
    case (Created(inventory), InventoryEvent.Reserved_V1(_, entityId, _, _, quantity, version, _))
        if inventory.id == entityId =>
      Created(inventory.reserve(quantity, inventory.version) match {
        case Right((reservedInventory, _)) => reservedInventory
        case Left(error) =>
          throw new IllegalStateException(s"Failed to reserve inventory: $error")
      })

    // 引当解放イベント
    case (Created(inventory), InventoryEvent.Released_V1(_, entityId, _, _, quantity, version, _))
        if inventory.id == entityId =>
      Created(inventory.release(quantity, inventory.version) match {
        case Right((releasedInventory, _)) => releasedInventory
        case Left(error) =>
          throw new IllegalStateException(s"Failed to release inventory: $error")
      })

    // 出庫イベント
    case (Created(inventory), InventoryEvent.Issued_V1(_, entityId, _, _, quantity, version, _))
        if inventory.id == entityId =>
      Created(inventory.issue(quantity, inventory.version) match {
        case Right((issuedInventory, _)) => issuedInventory
        case Left(error) =>
          throw new IllegalStateException(s"Failed to issue inventory: $error")
      })

    // 調整イベント
    case (
          Created(inventory),
          InventoryEvent.Adjusted_V1(_, entityId, _, _, _, newQuantity, reason, version, _)
        ) if inventory.id == entityId =>
      Created(inventory.adjust(newQuantity, reason, inventory.version) match {
        case Right((adjustedInventory, _)) => adjustedInventory
        case Left(error) =>
          throw new IllegalStateException(s"Failed to adjust inventory: $error")
      })

    case (NotCreated(id), _) =>
      throw new IllegalStateException(s"Cannot apply event $event to NotCreated state with id $id")

    case (Created(inventory), _) =>
      throw new IllegalStateException(
        s"Cannot apply event $event to Created state with inventory $inventory"
      )
  }
}
