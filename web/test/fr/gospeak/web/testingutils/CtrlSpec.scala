package fr.gospeak.web.testingutils

import akka.stream.Materializer
import com.mohiva.play.silhouette.api.Silhouette
import fr.gospeak.infra.libs.timeshape.TimeShape
import fr.gospeak.infra.services.InMemoryEmailSrv
import fr.gospeak.infra.services.storage.sql.GospeakDbSql
import fr.gospeak.web.AppConf
import fr.gospeak.web.auth.domain.CookieEnv
import fr.gospeak.web.auth.services.AuthSrv
import org.scalatest.{FunSpec, Matchers}
import play.api.mvc._
import play.api.test.NoMaterializer

trait CtrlSpec extends FunSpec with Matchers {
  // play
  protected val cc: ControllerComponents = Values.cc

  // silhouette
  protected val silhouette: Silhouette[CookieEnv] = Values.silhouette
  protected val unsecuredReq: RequestHeader = Values.unsecuredReqHeader
  protected val securedReq: RequestHeader = Values.securedReqHeader
  protected implicit val mat: Materializer = NoMaterializer

  // app
  protected val conf: AppConf = Values.conf
  protected val db: GospeakDbSql = Values.db
  protected val emailSrv: InMemoryEmailSrv = Values.emailSrv
  protected val authSrv: AuthSrv = Values.authSrv

  protected val timeShape: TimeShape = Values.timeShape
}
