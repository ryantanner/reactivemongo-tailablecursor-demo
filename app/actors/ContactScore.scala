package actors

import akka.actor._
import akka.pattern.ask
import akka.util.Timeout
import akka.event.Logging 
import akka.event.LoggingReceive
 
import scala.concurrent.duration._
import scala.concurrent.Future
 
import play.api._
import play.api.mvc._
import play.api.libs.json._
import play.api.libs.iteratee._
import play.api.libs.concurrent._
import play.api.libs.concurrent.Akka
import play.api.libs.concurrent.Execution.Implicits._
import play.api.Play.current

import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat

case class Message(js: JsValue)
case object Listen
case class Connected(enumerator: Enumerator[JsValue])

class ContactScore extends Actor {

  val messages = (__ \ "messages").json.pick[JsArray]
  val msgSender = (__ \\ "from_name").json.pick[JsString]
  val msgTime = (__ \\ "date").json.pick[JsString]

  val scoreInflation = 1.148698
  val inflationRefTime = 1356998400000l
  val msPerYear = 31536000000l
  val dtf = DateTimeFormat.forPattern("yyyy-MM-dd HH:mm:ss")

  def inflation(time: Long) = Math.pow(scoreInflation, ((time - inflationRefTime) / msPerYear))

  val (messageEnumerator, messageChannel) = Concurrent.broadcast[JsValue]
  var scores = Map.empty[String, Double]

  def receive = {

    case Message(js) =>
      js.transform(messages).map {
        case jsmessages:JsArray => jsmessages.as[Array[JsObject]].foreach { msg =>
          val name = msg.transform(msgSender).get.value
          val time = dtf.parseDateTime(msg.transform(msgTime).get.value).getMillis
          Logger.debug(scores.toString + "\n\n")
          if(scores.isDefinedAt(name))
            scores = scores - name + (name -> (scores(name) + inflation(time)))
          else
            scores = scores + (name -> inflation(time))
          messageChannel.push(Json.obj(name -> scores(name)))
        }
      }

    case Listen => sender ! Connected(messageEnumerator)

  }

}
