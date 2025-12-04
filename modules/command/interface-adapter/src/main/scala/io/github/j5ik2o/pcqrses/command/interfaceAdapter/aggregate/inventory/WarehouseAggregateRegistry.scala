package io.github.j5ik2o.pcqrses.command.interfaceAdapter.aggregate.inventory

import io.github.j5ik2o.pcqrses.command.domain.inventory.WarehouseId
import io.github.j5ik2o.pcqrses.command.interfaceAdapter.aggregate.WarehouseAggregate
import io.github.j5ik2o.pcqrses.command.interfaceAdapter.contract.inventory.WarehouseProtocol
import io.github.j5ik2o.pcqrses.command.interfaceAdapter.registry.GenericAggregateRegistry
import org.apache.pekko.actor.typed.{ActorSystem, Behavior}

import scala.concurrent.duration.FiniteDuration
import scala.util.Try

object WarehouseAggregateRegistry {

  private val StopMessageId: WarehouseId = WarehouseId.from("00000000000000000000000000")

  def create(
      mode: GenericAggregateRegistry.Mode = GenericAggregateRegistry.Mode.LocalMode,
      idleTimeout: Option[FiniteDuration] = None,
      enablePassivation: Boolean = true
  )(implicit system: ActorSystem[?]): Behavior[WarehouseProtocol.Command] =
    GenericAggregateRegistry.create[WarehouseId, WarehouseProtocol.Command](
      aggregateName = WarehouseId.EntityTypeName,
      mode = mode,
      idleTimeout = idleTimeout,
      enablePassivation = enablePassivation
    )(
      nameF = _.asString,
      aggregateBehavior = WarehouseAggregate.apply,
      extractId = str => Try(WarehouseId.from(str)),
      createIdleMessage = id => WarehouseProtocol.Stop(id),
      stopMessageId = Some(StopMessageId)
    )

  def modeFromConfig(system: ActorSystem[?]): GenericAggregateRegistry.Mode =
    GenericAggregateRegistry.modeFromConfig(system)
}
