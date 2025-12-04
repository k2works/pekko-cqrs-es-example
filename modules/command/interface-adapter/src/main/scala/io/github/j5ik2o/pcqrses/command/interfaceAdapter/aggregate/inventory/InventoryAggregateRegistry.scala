package io.github.j5ik2o.pcqrses.command.interfaceAdapter.aggregate.inventory

import io.github.j5ik2o.pcqrses.command.domain.inventory.InventoryId
import io.github.j5ik2o.pcqrses.command.interfaceAdapter.contract.inventory.InventoryProtocol
import io.github.j5ik2o.pcqrses.command.interfaceAdapter.registry.GenericAggregateRegistry
import org.apache.pekko.actor.typed.{ActorSystem, Behavior}

import scala.concurrent.duration.FiniteDuration
import scala.util.Try

object InventoryAggregateRegistry {

  private val StopMessageId: InventoryId = InventoryId.from("00000000000000000000000000")

  def create(
      mode: GenericAggregateRegistry.Mode = GenericAggregateRegistry.Mode.LocalMode,
      idleTimeout: Option[FiniteDuration] = None,
      enablePassivation: Boolean = true
  )(implicit system: ActorSystem[?]): Behavior[InventoryProtocol.Command] =
    GenericAggregateRegistry.create[InventoryId, InventoryProtocol.Command](
      aggregateName = InventoryId.EntityTypeName,
      mode = mode,
      idleTimeout = idleTimeout,
      enablePassivation = enablePassivation
    )(
      nameF = _.asString,
      aggregateBehavior = InventoryAggregate.apply,
      extractId = str => Try(InventoryId.from(str)),
      createIdleMessage = id => InventoryProtocol.Stop(id),
      stopMessageId = Some(StopMessageId)
    )

  def modeFromConfig(system: ActorSystem[?]): GenericAggregateRegistry.Mode =
    GenericAggregateRegistry.modeFromConfig(system)
}
