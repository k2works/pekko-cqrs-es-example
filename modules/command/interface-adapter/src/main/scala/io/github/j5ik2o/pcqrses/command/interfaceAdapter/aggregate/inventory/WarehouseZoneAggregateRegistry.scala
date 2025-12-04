package io.github.j5ik2o.pcqrses.command.interfaceAdapter.aggregate.inventory

import io.github.j5ik2o.pcqrses.command.domain.inventory.WarehouseZoneId
import io.github.j5ik2o.pcqrses.command.interfaceAdapter.contract.inventory.WarehouseZoneProtocol
import io.github.j5ik2o.pcqrses.command.interfaceAdapter.registry.GenericAggregateRegistry
import org.apache.pekko.actor.typed.{ActorSystem, Behavior}

import scala.concurrent.duration.FiniteDuration
import scala.util.Try

object WarehouseZoneAggregateRegistry {

  private val StopMessageId: WarehouseZoneId = WarehouseZoneId.from("00000000000000000000000000")

  def create(
      mode: GenericAggregateRegistry.Mode = GenericAggregateRegistry.Mode.LocalMode,
      idleTimeout: Option[FiniteDuration] = None,
      enablePassivation: Boolean = true
  )(implicit system: ActorSystem[?]): Behavior[WarehouseZoneProtocol.Command] =
    GenericAggregateRegistry.create[WarehouseZoneId, WarehouseZoneProtocol.Command](
      aggregateName = WarehouseZoneId.EntityTypeName,
      mode = mode,
      idleTimeout = idleTimeout,
      enablePassivation = enablePassivation
    )(
      nameF = _.asString,
      aggregateBehavior = WarehouseZoneAggregate.apply,
      extractId = str => Try(WarehouseZoneId.from(str)),
      createIdleMessage = id => WarehouseZoneProtocol.Stop(id),
      stopMessageId = Some(StopMessageId)
    )

  def modeFromConfig(system: ActorSystem[?]): GenericAggregateRegistry.Mode =
    GenericAggregateRegistry.modeFromConfig(system)
}
