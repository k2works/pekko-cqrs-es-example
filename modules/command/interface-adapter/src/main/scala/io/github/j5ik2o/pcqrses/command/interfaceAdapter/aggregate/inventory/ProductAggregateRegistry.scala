package io.github.j5ik2o.pcqrses.command.interfaceAdapter.aggregate.inventory

import io.github.j5ik2o.pcqrses.command.domain.inventory.ProductId
import io.github.j5ik2o.pcqrses.command.interfaceAdapter.aggregate.ProductAggregate
import io.github.j5ik2o.pcqrses.command.interfaceAdapter.contract.inventory.ProductProtocol
import io.github.j5ik2o.pcqrses.command.interfaceAdapter.registry.GenericAggregateRegistry
import org.apache.pekko.actor.typed.{ActorSystem, Behavior}

import scala.concurrent.duration.FiniteDuration
import scala.util.Try

object ProductAggregateRegistry {

  private val StopMessageId: ProductId = ProductId.from("00000000000000000000000000")

  def create(
      mode: GenericAggregateRegistry.Mode = GenericAggregateRegistry.Mode.LocalMode,
      idleTimeout: Option[FiniteDuration] = None,
      enablePassivation: Boolean = true
  )(implicit system: ActorSystem[?]): Behavior[ProductProtocol.Command] =
    GenericAggregateRegistry.create[ProductId, ProductProtocol.Command](
      aggregateName = ProductId.EntityTypeName,
      mode = mode,
      idleTimeout = idleTimeout,
      enablePassivation = enablePassivation
    )(
      nameF = _.asString,
      aggregateBehavior = ProductAggregate.apply,
      extractId = str => Try(ProductId.from(str)),
      createIdleMessage = id => ProductProtocol.Stop(id),
      stopMessageId = Some(StopMessageId)
    )

  def modeFromConfig(system: ActorSystem[?]): GenericAggregateRegistry.Mode =
    GenericAggregateRegistry.modeFromConfig(system)
}
