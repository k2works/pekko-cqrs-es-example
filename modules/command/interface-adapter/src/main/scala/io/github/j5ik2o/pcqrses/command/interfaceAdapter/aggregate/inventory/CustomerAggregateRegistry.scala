package io.github.j5ik2o.pcqrses.command.interfaceAdapter.aggregate.inventory

import io.github.j5ik2o.pcqrses.command.domain.inventory.CustomerId
import io.github.j5ik2o.pcqrses.command.interfaceAdapter.contract.inventory.CustomerProtocol
import io.github.j5ik2o.pcqrses.command.interfaceAdapter.registry.GenericAggregateRegistry
import org.apache.pekko.actor.typed.{ActorSystem, Behavior}

import scala.concurrent.duration.FiniteDuration
import scala.util.Try

object CustomerAggregateRegistry {

  private val StopMessageId: CustomerId = CustomerId.from("00000000000000000000000000")

  def create(
      mode: GenericAggregateRegistry.Mode = GenericAggregateRegistry.Mode.LocalMode,
      idleTimeout: Option[FiniteDuration] = None,
      enablePassivation: Boolean = true
  )(implicit system: ActorSystem[?]): Behavior[CustomerProtocol.Command] =
    GenericAggregateRegistry.create[CustomerId, CustomerProtocol.Command](
      aggregateName = CustomerId.EntityTypeName,
      mode = mode,
      idleTimeout = idleTimeout,
      enablePassivation = enablePassivation
    )(
      nameF = _.asString,
      aggregateBehavior = CustomerAggregate.apply,
      extractId = str => Try(CustomerId.from(str)),
      createIdleMessage = id => CustomerProtocol.Stop(id),
      stopMessageId = Some(StopMessageId)
    )

  def modeFromConfig(system: ActorSystem[?]): GenericAggregateRegistry.Mode =
    GenericAggregateRegistry.modeFromConfig(system)
}
