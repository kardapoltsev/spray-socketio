package spray.contrib.socketio

import akka.actor.{ ActorLogging }
import akka.contrib.pattern.{ DistributedPubSubMediator, DistributedPubSubExtension, ShardRegion }
import akka.persistence.{ PersistenceFailure, EventsourcedProcessor }
import spray.contrib.socketio
import spray.contrib.socketio.packet.Packet

object ClusterConnectionActive {
  lazy val idExtractor: ShardRegion.IdExtractor = {
    case cmd: ConnectionActive.Command => (cmd.sessionId, cmd)
  }

  lazy val shardResolver: ShardRegion.ShardResolver = {
    case cmd: ConnectionActive.Command => (math.abs(cmd.sessionId.hashCode) % 100).toString
  }
}

/**
 *
 * transportConnection <1..n--1> connectionActive <1--1> connContext <1--n> transport
 */
class ClusterConnectionActive extends ConnectionActive with EventsourcedProcessor with ActorLogging {
  import ConnectionActive._

  // have to call after log created
  enableCloseTimeout()

  val mediator = DistributedPubSubExtension(context.system).mediator

  def publishMessage(msg: Any)(ctx: ConnectionContext) {
    msg match {
      case packet: Packet => mediator ! DistributedPubSubMediator.Publish(socketio.namespaceFor(packet.endpoint), OnPacket(packet, ctx))
      case x: OnBroadcast => mediator ! DistributedPubSubMediator.Publish(socketio.namespaceFor(x.endpoint), x)
    }
  }

  def receiveRecover: Receive = {
    case event: Event => update(event)
  }

  def receiveCommand: Receive = working orElse {
    case PersistenceFailure(_, _, ex) => log.error("Failed to persistence: {}", ex.getMessage)
  }

  override def processNewConnected(conn: Connected) {
    persist(conn)(super.processNewConnected(_))
  }

  override def processUpdatePackets(packets: UpdatePackets) {
    if (packets.packets.isEmpty && pendingPackets.isEmpty) {
      super.processUpdatePackets(packets)
    } else {
      persist(packets)(super.processUpdatePackets(_))
    }
  }

}
