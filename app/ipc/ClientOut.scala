package lila.ws
package ipc

import chess.format.FEN
import chess.Pos
import chess.variant.Variant
import play.api.libs.json._

import lila.ws.util.LilaJsObject.augment

sealed trait ClientOut

object ClientOut {

  case class Ping(lag: Option[Int]) extends ClientOut

  case class Watch(ids: Set[Game.ID]) extends ClientOut

  case object MoveLat extends ClientOut

  case object Notified extends ClientOut

  case object FollowingOnline extends ClientOut

  case class Opening(variant: Variant, path: Path, fen: FEN) extends ClientOut

  case class AnaMove(
      orig: Pos,
      dest: Pos,
      fen: FEN,
      path: Path,
      variant: Variant,
      chapterId: Option[ChapterId],
      promotion: Option[chess.PromotableRole]
  ) extends ClientOut

  implicit val jsonRead = Reads[ClientOut] { js =>
    (js match {
      case JsNull => Some(Ping(None))
      case o: JsObject => o str "t" flatMap {
        case "p" => Some(Ping(o int "l"))
        case "startWatching" => o.str("d").map(_ split " " toSet) map Watch.apply
        case "moveLat" => Some(MoveLat)
        case "notified" => Some(Notified)
        case "following_online" => Some(Notified)
        case "opening" => for {
          d <- o obj "d"
          path <- d str "path"
          fen <- d str "fen"
          variant = dataVariant(d)
        } yield Opening(variant, Path(path), FEN(fen))
        case "anaMove" => for {
          d <- o obj "d"
          orig <- d str "orig" flatMap Pos.posAt
          dest <- d str "dest" flatMap Pos.posAt
          path <- d str "path"
          fen <- d str "fen"
          variant = dataVariant(d)
          chapterId = d str "ch" map ChapterId.apply
          promotion = d str "promotion" flatMap chess.Role.promotable
        } yield AnaMove(orig, dest, FEN(fen), Path(path), variant, chapterId, promotion)
        case _ => None
      }
      case _ => None
    }) map { JsSuccess(_) } getOrElse JsError(s"Invalid ClientOut $js")
  }

  private def dataVariant(d: JsObject): Variant = Variant.orDefault(d str "variant" getOrElse "")
}
