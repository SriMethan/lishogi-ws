package lila.ws

import akka.actor.typed.scaladsl.{ Behaviors, ActorContext }
import akka.actor.typed.{ ActorRef, Behavior, PostStop }
import akka.stream.scaladsl._
import play.api.Logger

import ipc._
import sm._
import util.Util.nowSeconds

object ClientActor {

  def onStart(deps: Deps, ctx: ActorContext[ClientMsg]): Unit = {
    import deps._
    CountSM.connect
    bus.subscribe(ctx.self, _ sri req.sri)
    bus.subscribe(ctx.self, _.all)
    req.flag foreach { f =>
      bus.subscribe(ctx.self, _ flag f.value)
    }
  }

  def onStop(state: State, deps: Deps, ctx: ActorContext[ClientMsg]): Unit = {
    import deps._
    CountSM.disconnect
    req.user foreach { u =>
      queue(_.user, UserSM.Disconnect(u, ctx.self))
    }
    if (state.watchedGames.nonEmpty) queue(_.fen, FenSM.Unwatch(state.watchedGames, ctx.self))
    bus unsubscribe ctx.self
  }

  def socketControl(state: State, flag: Option[Flag], msg: ClientCtrl): Behavior[ClientMsg] = msg match {

    case ClientCtrl.Broom(oldSeconds) =>
      if (state.lastPing < oldSeconds && !flag.contains(Flag.api)) Behaviors.stopped
      else Behaviors.same

    case ClientCtrl.Disconnect =>
      Behaviors.stopped
  }

  def sitePing(state: State, deps: Deps, msg: ClientOut.Ping): State = {
    for { l <- msg.lag; u <- deps.req.user } deps.queue(_.lag, UserLag(u.id, l))
    state.copy(lastPing = nowSeconds)
  }

  def globalReceive(state: State, deps: Deps, ctx: ActorContext[ClientMsg], msg: ClientOutSite): State = {

    import state._
    import deps._

    msg match {

      case msg: ClientOut.Ping =>
        clientIn(ClientIn.Pong)
        sitePing(state, deps, msg)

      case ClientOut.Watch(gameIds) =>
        queue(_.fen, FenSM.Watch(gameIds, ctx.self))
        state.copy(watchedGames = state.watchedGames ++ gameIds)

      case msg: ClientOut if deps.req.flag.contains(Flag.api) =>
        if (state.ignoreLog) state
        else {
          Logger("SiteClient").info(s"API socket doesn't support $msg $req")
          state.copy(ignoreLog = true)
        }

      case ClientOut.MoveLat =>
        bus.subscribe(ctx.self, _.mlat)
        state

      case ClientOut.Notified =>
        req.user foreach { u =>
          queue(_.notified, LilaIn.Notified(u.id))
        }
        state

      case ClientOut.FollowingOnline =>
        req.user foreach { u =>
          queue(_.friends, LilaIn.Friends(u.id))
        }
        state

      case opening: ClientOut.Opening =>
        Chess(opening) foreach clientIn
        state

      case anaMove: ClientOut.AnaMove =>
        clientIn(Chess(anaMove))
        state

      case anaDrop: ClientOut.AnaDrop =>
        clientIn(Chess(anaDrop))
        state

      case anaDests: ClientOut.AnaDests =>
        clientIn(Chess(anaDests))
        state

      case ClientOut.SiteForward(payload) =>
        queue(_.site, LilaIn.TellSri(req.sri, req.user.map(_.id), payload))
        state

      case ClientOut.Unexpected(msg) =>
        Monitor.clientOutUnexpected.increment()
        if (state.ignoreLog) state
        else {
          Logger("ClientActor").info(s"Unexpected $msg $req")
          state.copy(ignoreLog = true)
        }

      case ClientOut.Ignore =>
        state
    }
  }

  case class State(
      watchedGames: Set[Game.Id] = Set.empty,
      lastPing: Int = nowSeconds,
      ignoreLog: Boolean = false
  )

  def Req(req: play.api.mvc.RequestHeader, sri: Sri, user: Option[User], flag: Option[Flag] = None): Req = Req(
    name = lila.ws.util.Util.reqName(req),
    ip = IpAddress(req.remoteAddress),
    sri = sri,
    user = user,
    flag = flag
  )

  case class Req(
      name: String,
      ip: IpAddress,
      sri: Sri,
      flag: Option[Flag],
      user: Option[User]
  ) {
    override def toString = s"${user getOrElse "Anon"} $name"
  }

  case class Deps(
      client: SourceQueue[ClientIn],
      queue: Stream.Queues,
      req: Req,
      bus: Bus
  ) {
    def clientIn(msg: ClientIn): Unit = client offer msg
  }
}
