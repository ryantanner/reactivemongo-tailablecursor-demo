package controllers

import play.api._
import play.api.mvc._
import play.api.Play.current
import play.api.libs.json._
import play.api.libs.iteratee._

import scala.concurrent.{ ExecutionContext, Future }

import play.modules.reactivemongo._
import play.modules.reactivemongo.json.collection.JSONCollection

import java.io.File

import reactivemongo.api._

object Importer extends Controller with MongoController {

  val collection = db.collection[JSONCollection]("messages")

  def index = Action {
    Ok(views.html.importer.index())
  }

  def upload = Action(parse.temporaryFile) { request =>
    val fileSource = new File("/tmp/messages-dump")
    request.body.moveTo(fileSource, true)

    val lines = io.Source.fromFile(fileSource).getLines.toList.drop(4).dropRight(2)

    val columns = lines.head.split("\\|").map(_.replace("\"",""))

    val accountIdIndex = columns.indexOf("account_id")

    val msgs = lines.tail.foldLeft(Map.empty[Int, List[JsObject]]) { (acc, l) =>
      val content = l.split("\\|").map(_.replace("\"", ""))
      
      val accountId = content(accountIdIndex).toInt
      
      if(acc.isDefinedAt(accountId))
        acc - accountId + (accountId -> (acc(accountId) :+
          JsObject((columns zip content.map(JsString(_))))
        ))
      else
        acc + (accountId -> List(JsObject((columns zip content.map(JsString(_))))))
    }

    msgs.map( kv =>
      Json.obj("uid" -> kv._1.toString, "messages" -> kv._2)
    ).foreach(collection.insert)


    Ok("inserted messages")

  }

}

