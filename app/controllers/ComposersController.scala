package controllers

import javax.inject._
import java.io.ByteArrayInputStream

import scala.concurrent.{ Future, ExecutionContext }
import scala.concurrent.duration._
import scala.collection.immutable.ListMap

import play.api.Configuration
import play.api._
import play.api.mvc._
import play.api.libs.ws._
import play.api.http.HttpEntity
import play.api.libs.json._
import play.api.libs.functional.syntax._
import play.api.i18n.I18nSupport
import play.api.libs.concurrent.Futures._ 
import play.api.libs.concurrent.Futures 

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl._
import akka.util.{ ByteString, Timeout }

case class Summary(version: String, resourceTitle: String, resourceId: String, eadLocation: String, scope: String, biog: String)
case class DetailParent(title: String, biogHist: Vector[String])
case class Detail(cuid: String, title: String, extent: Option[String], url: String, resourceIdentifier: String, resourceTitle: String, summaryUrl: String, parent: Option[DetailParent], accessRestrictions: Option[Vector[String]], isHandle: Option[String])
case class Archiveit(title: String, extent: String, display_url: String)

@Singleton
class ComposersController @Inject()
  (config: Configuration)
  (cc: ControllerComponents)
  (ws: WSClient)
  (implicit ec:ExecutionContext, implicit val futures: Futures) 
    extends AbstractController(cc) {

  val aspaceUrl = config.get[String]("aspaceUrl")
  val basePlugin = config.get[String]("basePlugin")
  val rootUrl = config.get[String]("rootUrl")
  val repoId = config.get[String]("repoId")

  
  implicit val archiveitWrites: Writes[Archiveit] = (
    (JsPath \ "title").write[String] and
    (JsPath \ "extent").write[String] and
    (JsPath \ "display_url").write[String])(unlift(Archiveit.unapply))

  
  def index() = Action {
    Ok(views.html.index())
  }

  def authenticate(repo_id: String, controller: String, identifier: String) = Action.async { implicit request: Request[AnyContent] =>

    val request = ws.url(aspaceUrl + "users/" + config.get[String]("apiUser") + "/login").post(Map("password" -> config.get[String]("apiPass")))
    
    request.withTimeout(5.seconds).map { response =>
      
      val json = Json.parse(response.body).asInstanceOf[JsObject]
      json.keys.contains("session") match {
        case true => {
            Redirect("/" + controller + "/" + repo_id + "/" + identifier)
              .withSession("aspace-session" -> json("session").as[String].stripLineEnd)
        }
        case false => InternalServerError("Unable to connect to archivesspace")
      }
    }.recover {
      case e: scala.concurrent.TimeoutException => InternalServerError("timeout")
    }
  }

 def archiveit(repo_id: String, identifier: String) = Action.async { implicit request: Request[AnyContent] =>

  request.session.get("aspace-session").map { token => 

      ws.url(aspaceUrl + basePlugin + "repositories/"  + repoId + "/archiveit/" + identifier)
        .addHttpHeaders("X-Archivesspace-Session" -> token)
        .get().withTimeout(5.seconds)
        .map { response => 

          response.status match {
            case 200 => {
              val json = Json.parse(response.body)
              val archiveIt = new Archiveit(json("title").as[String], 
              json("extent").as[String], 
              rootUrl + "summary/" + identifier) 
              Ok(Json.toJson(archiveIt))
            }

            case 400 => NotFound(Json.toJson(Map("error" -> ("Resource not found for identifier: " + identifier))))
            case 403 => Redirect("/authenticate/" + repo_id + "/archiveit/" + identifier)
            case 412 => Redirect("/authenticate/" + repo_id + "/archiveit/" + identifier)
            case 500 => InternalServerError
            case default => InternalServerError
          }
      }
    }.getOrElse(Future(Redirect("/authenticate/archiveit/" + identifier)))
    
  }



  def summary(repo_id: String, identifier: String) = Action.async { implicit request: Request[AnyContent] =>

  	var doss = Map[String, JsObject]()

    request.session.get("aspace-session").map { token =>
      ws.url(aspaceUrl + basePlugin + "repositories/"  + repoId + "/summary/" + identifier)
        .addHttpHeaders("X-Archivesspace-Session" -> token)
        .get().withTimeout(5.seconds)
        .map { response =>
          
          response.status match {
            case 200 => {
          		val json = Json.parse(response.body)
          		val version = json("version").as[String]
          		val resourceTitle = json("resource_title").as[String]
          		val resourceId = json("resource_identifier").as[String]
          		val eadLocation = json("ead_location").as[String]
          		val scope = json("scopecontent").as[String]
          		val biog = json("bioghist").as[String]
          		val summary = new Summary(version, resourceTitle,resourceId, eadLocation, scope, biog)
          		val dos = json("digital_objects").as[Vector[JsObject]]

              for(digital_obj <- dos) {
                doss = doss + ((digital_obj \ "component_id").as[String] -> digital_obj)
              }

        		  Ok(views.html.summary(summary, ListMap(doss.toSeq.sortWith(_._1 < _._1):_*), rootUrl))
            }

            case 400 => NotFound(Json.toJson(Map("error" -> ("Resource not found for identifier: " + identifier))))
            case 403 => Redirect("/authenticate/" + repo_id + "/summary/" + identifier)
            case 412 => Redirect("/authenticate/" + repo_id + "/summary/" + identifier)
            case 500 => InternalServerError
            case default => InternalServerError

          }  
    	}

    }.getOrElse {
      Future(Redirect("/authenticate/" + repo_id + "/summary/" + identifier))
    }
  }

  def detail(repo_id: String, identifier: String) = Action.async { implicit request: Request[AnyContent] =>

    request.session.get("aspace-session").map { token =>
      ws.url(aspaceUrl + basePlugin + "repositories/"  + repoId + "/detailed/" + identifier)
        .addHttpHeaders("X-Archivesspace-Session" -> token)
        .get().withTimeout(5.seconds)
        .map { response =>
          
          response.status match {
            case 200 => {
              val json = Json.parse(response.body)
              val ao = json("archival_object")
              val cuid = ao("component_id").as[String]
              val title = ao("title").as[String]
              val extent = ao("extent").asOpt[String]
              val urls = ao("file_uris").as[Vector[String]]
              val resourceIdentifier = ao("resource_identifier").as[String]
              val resourceTitle = ao("resource_title").as[String]
              val summary_url = (rootUrl + "summary/" + resourceIdentifier)
              val handleRegex = "hdl.handle.net".r
              val url = handleRegex.findFirstIn(urls(0))

              ao("restrictions_apply").as[Boolean] match {
                case true => {


                  val aeonUrl = "https://aeon.library.nyu.edu/Logon?Action=10&Form=31&Value=http://dlib.nyu.edu/findingaids/ead/fales/" + resourceIdentifier.replace(".", "_").toLowerCase + ".xml&view=xml"
                  val accessRestrictions = ao("accessrestrict").as[Vector[String]]
                  val dao = new Detail(cuid, title, extent, aeonUrl, resourceIdentifier, resourceTitle, summary_url, getParent(json("parent_object").as[JsValue]), Some(accessRestrictions), None)
                  Ok(views.html.restricted(dao))
                }

                case false => {
                  val dao = new Detail(cuid, title, extent, urls(0), resourceIdentifier, resourceTitle, summary_url, getParent(json("parent_object").as[JsValue]), None, url)
                  Ok(views.html.detail(dao))
                }
              }
            }

            case 400 => NotFound(Json.toJson(Map("error" -> ("Resource not found for identifier: " + identifier))))
            case 403 => Redirect("/authenticate/" + repo_id + "/detailed/" + identifier)
            case 412 => Redirect("/authenticate/" + repo_id + "/detailed/" + identifier)
            case 500 => InternalServerError
            case default => InternalServerError

          }  
      }

    }.getOrElse {
      Future(Redirect("/authenticate/" + repo_id + "/detailed/" + identifier))
    }
  }


    def getParent(jsValue: JsValue): Option[DetailParent] = {
    (jsValue != JsNull) match {
      case true => Some(new DetailParent(jsValue("title").as[String], jsValue("bioghist").as[Vector[String]]))
      case false => None
    }
  }
}
