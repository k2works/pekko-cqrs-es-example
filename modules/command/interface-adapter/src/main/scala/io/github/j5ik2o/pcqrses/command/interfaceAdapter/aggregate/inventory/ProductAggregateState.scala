package io.github.j5ik2o.pcqrses.command.interfaceAdapter.aggregate.inventory

import io.github.j5ik2o.pcqrses.command.domain.inventory.*

enum ProductAggregateState {
  case NotCreated(id: ProductId)
  case Created(product: Product)
  case Obsoleted(product: Product)

  def applyEvent(event: ProductEvent): ProductAggregateState = (this, event) match {
    case (
          NotCreated(id),
          ProductEvent.Created_V1(_, entityId, productCode, name, categoryCode, storageCondition, _)
        ) if id == entityId =>
      Created(Product(entityId, productCode, name, categoryCode, storageCondition)._1)

    case (
          Created(product),
          ProductEvent.Updated_V1(
            _,
            entityId,
            _,
            newName,
            _,
            newCategoryCode,
            _,
            newStorageCondition,
            _
          )
        ) if product.id == entityId =>
      Created(product.update(newName, newCategoryCode, newStorageCondition) match {
        case Right((updatedProduct, _)) => updatedProduct
        case Left(error) =>
          throw new IllegalStateException(s"Failed to update product: $error")
      })

    case (Created(product), ProductEvent.Obsoleted_V1(_, entityId, _)) if product.id == entityId =>
      Obsoleted(product.obsolete match {
        case Right((obsoletedProduct, _)) => obsoletedProduct
        case Left(error) =>
          throw new IllegalStateException(s"Failed to obsolete product: $error")
      })

    case (NotCreated(id), _) =>
      throw new IllegalStateException(s"Cannot apply event $event to NotCreated state with id $id")

    case (Created(product), _) =>
      throw new IllegalStateException(s"Cannot apply event $event to Created state with product $product")

    case (Obsoleted(product), _) =>
      throw new IllegalStateException(s"Cannot apply event $event to Obsoleted state with product $product")
  }
}
