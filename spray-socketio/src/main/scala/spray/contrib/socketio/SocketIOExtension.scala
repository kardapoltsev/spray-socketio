package spray.contrib.socketio

import akka.actor._
import akka.contrib.pattern._
import akka.cluster.Cluster
import akka.routing.{ BroadcastRoutingLogic, ConsistentHashingRoutingLogic, RoundRobinRoutingLogic, RandomRoutingLogic }
import spray.contrib.socketio

object SocketIOExtension extends ExtensionId[SocketIOExtension] with ExtensionIdProvider {
  override def get(system: ActorSystem): SocketIOExtension = super.get(system)

  override def lookup(): ExtensionId[_ <: Extension] = SocketIOExtension

  override def createExtension(system: ExtendedActorSystem): SocketIOExtension = new SocketIOExtension(system)

  val mediatorName: String = "socketioMediator"
  val mediatorSingleton: String = "active"

}

class SocketIOExtension(system: ExtendedActorSystem) extends Extension {
  /**
   * INTERNAL API
   */
  private[socketio] object Settings {
    val config = system.settings.config.getConfig("spray.socketio")
    val isCluster: Boolean = config.getString("mode") == "cluster"
    val ConnRole: String = "connectionActive"
    val enableConnPersistence: Boolean = config.getBoolean("server.enable-connectionactive-persistence")
  }

  import Settings._

  lazy val localMediator = system.actorOf(LocalMediator.props(), name = SocketIOExtension.mediatorName)

  /**
   * Need to start immediately to accept broadcast etc.
   */
  val broadcastMediator = if (Settings.isCluster) DistributedPubSubExtension(system).mediator else localMediator

  /**
   * Need to start immediately to accept subscriptions msg etc.
   */
  val namespaceMediator = if (Settings.isCluster) {
    val cluster = Cluster(system)
    if (cluster.getSelfRoles.contains(ConnRole)) {
      val routingLogic = Settings.config.getString("routing-logic") match {
        case "random"             => RandomRoutingLogic()
        case "round-robin"        => RoundRobinRoutingLogic()
        case "consistent-hashing" => ConsistentHashingRoutingLogic(system)
        case "broadcast"          => BroadcastRoutingLogic()
        case other                => throw new IllegalArgumentException(s"Unknown 'routing-logic': [$other]")
      }
      val ref = system.actorOf(DistributedBalancingPubSubMediator.props(Some(ConnRole), routingLogic), name = SocketIOExtension.mediatorName)
      ClusterReceptionistExtension(system).registerService(ref)
      ref
    } else {
      system.deadLetters
    }
  } else localMediator

  lazy val connectionActiveProps: Props = if (enableConnPersistence) {
    PersistentConnectionActive.props(namespaceMediator, broadcastMediator)
  } else {
    TransientConnectionActive.props(namespaceMediator, broadcastMediator)
  }

  if (Settings.isCluster) {
    ConnectionActive.startShard(system, connectionActiveProps)
  }

  lazy val resolver = if (Settings.isCluster) {
    ClusterSharding(system).shardRegion(ConnectionActive.shardName)
  } else {
    system.actorOf(LocalConnectionActiveResolver.props(localMediator, connectionActiveProps), name = ConnectionActive.shardName)
  }

}
