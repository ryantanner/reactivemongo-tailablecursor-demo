/*
 * Copyright 2012 Stephane Godbillon
 *
 * This sample is in the public domain.
 */
package controllers

import play.api._
import play.api.mvc._
import play.api.Play.current
import play.api.libs.json._
import play.api.libs.iteratee._

import scala.concurrent._
import play.api.libs.concurrent.Akka
import scala.concurrent.duration._
import scala.concurrent.duration.Duration
import scala.concurrent.Await
import scala.concurrent.Future
import akka.util.Timeout
import akka.actor.Props
import akka.pattern.ask

import play.modules.reactivemongo._
import play.modules.reactivemongo.json.collection.JSONCollection

import reactivemongo.api._

import actors._

object Application extends Controller with MongoController {

  implicit val timeout = Timeout(1 second)

  val source = Akka.system.actorOf(Props[ContactScore])

  // let's be sure that the collections exists and is capped
  val futureCollection: Future[JSONCollection] = {
    val db = ReactiveMongoPlugin.db
    val collection = db.collection[JSONCollection]("messages")
    collection.stats().flatMap {
      case stats if !stats.capped =>
        // the collection is not capped, so we convert it
        println("converting to capped")
        collection.convertToCapped(1024 * 1024, None)
      case _ => Future(collection)
    }.recover {
      // the collection does not exist, so we create it
      case _ =>
        println("creating capped collection...")
        collection.createCapped(1024 * 1024, None)
    }.map { _ =>
      println("the capped collection is available")
      collection
    }
  }
  
  def index = Action {
    Ok(views.html.index())
  }

  def watchCollection = WebSocket.using[JsValue] { request =>
    // Inserts the received messages into the capped collection
    val in = Iteratee.flatten(futureCollection.map(collection => Iteratee.foreach[JsValue] { json =>
      println("received " + json)
      collection.insert(json)
    }))

    // Enumerates the capped collection
    val out = {
      val futureEnumerator = futureCollection.map { collection =>
        // so we are sure that the collection exists and is a capped one
        val cursor: Cursor[JsValue] = collection
          // we want all the documents
          .find(Json.obj("uid" -> "1"))
          // the cursor must be tailable and await data
          .options(QueryOpts().tailable.awaitData)
          .cursor[JsValue]

        // ok, let's enumerate it
        cursor.enumerate
      }

      val scorer = Iteratee.foreach[JsValue] { json =>
        Logger.debug("sending json")
        source ! Message(json)
      }

      Enumerator.flatten(futureEnumerator)(scorer)
      Enumerator.flatten(futureEnumerator)
    }

    // We're done!
    (in, out)
  }

  def watchScore = WebSocket.using[JsValue] { request =>

    Logger.debug("starting score...")

    val in = Iteratee.foreach[JsValue] { json =>
      println(json)
    }

    val out = Enumerator.flatten((source ? Listen).map {
        case Connected(enumerator) => enumerator
      })

    out(in)

    (in, out)

  }

}
