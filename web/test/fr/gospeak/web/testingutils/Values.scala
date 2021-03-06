package fr.gospeak.web.testingutils

import java.util.UUID

import akka.stream.Materializer
import com.danielasfregola.randomdatagenerator.RandomDataGenerator
import com.mohiva.play.silhouette.api.actions._
import com.mohiva.play.silhouette.api.util.Clock
import com.mohiva.play.silhouette.api.{LoginInfo, Silhouette, SilhouetteProvider, Environment => SilhouetteEnvironment}
import com.mohiva.play.silhouette.impl.authenticators.CookieAuthenticator
import com.mohiva.play.silhouette.test._
import com.typesafe.config.ConfigFactory
import fr.gospeak.core.ApplicationConf
import fr.gospeak.core.domain.User
import fr.gospeak.core.testingutils.Generators._
import fr.gospeak.infra.libs.timeshape.TimeShape
import fr.gospeak.infra.services.storage.sql.{DatabaseConf, GospeakDbSql}
import fr.gospeak.infra.services.{GravatarSrv, InMemoryEmailSrv}
import fr.gospeak.web.AppConf
import fr.gospeak.web.auth.domain.{AuthUser, CookieEnv}
import fr.gospeak.web.auth.services.{AuthRepo, AuthSrv}
import fr.gospeak.web.domain.Breadcrumb
import fr.gospeak.web.utils.{SecuredReq, UserAwareReq}
import play.api.i18n.Messages
import play.api.mvc._
import play.api.test.CSRFTokenHelper._
import play.api.test.{CSRFTokenHelper, FakeRequest, Helpers, NoMaterializer}

import scala.concurrent.ExecutionContext.Implicits.global

object Values extends RandomDataGenerator {
  // play
  // private val playEnv = Environment.simple()
  // private val ctx = ApplicationLoader.Context.create(playEnv)
  // private val gsComponents = new GospeakComponents(ctx)
  val cc: ControllerComponents = Helpers.stubControllerComponents()
  private val playBodyParsers = cc.parsers
  private val messagesApi = cc.messagesApi
  private val bodyParsers: BodyParsers.Default = new BodyParsers.Default(playBodyParsers)

  // silhouette
  private val user: User = random[User]
  private val loginInfo: LoginInfo = AuthSrv.loginInfo(user.email)
  private val identity: AuthUser = AuthUser(loginInfo, user, Seq())
  protected val clock = Clock()
  private val env: SilhouetteEnvironment[CookieEnv] = FakeEnvironment[CookieEnv](Seq(identity.loginInfo -> identity))
  private val securedAction: SecuredAction = new DefaultSecuredAction(new DefaultSecuredRequestHandler(new DefaultSecuredErrorHandler(messagesApi)), bodyParsers)
  private val unsecuredAction: UnsecuredAction = new DefaultUnsecuredAction(new DefaultUnsecuredRequestHandler(new DefaultUnsecuredErrorHandler(messagesApi)), bodyParsers)
  private val userAwareAction: UserAwareAction = new DefaultUserAwareAction(new DefaultUserAwareRequestHandler(), bodyParsers)
  val silhouette: Silhouette[CookieEnv] = new SilhouetteProvider(env, securedAction, unsecuredAction, userAwareAction)
  val unsecuredReqHeader: RequestHeader = FakeRequest().withCSRFToken
  val securedReqHeader: RequestHeader = FakeRequest().withAuthenticator(identity.loginInfo)(env).withCSRFToken
  protected implicit val mat: Materializer = NoMaterializer

  // app
  val conf: AppConf = AppConf.load(ConfigFactory.load()).get
  val db: GospeakDbSql = new GospeakDbSql(DatabaseConf.H2(s"jdbc:h2:mem:${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1"), conf.gospeak)
  private val authRepo = new AuthRepo(db.user, db.group)
  val emailSrv = new InMemoryEmailSrv()
  val authSrv = AuthSrv(conf.auth, silhouette, db.user, db.userRequest, db.group, authRepo, clock, new GravatarSrv())

  // twirl
  private val req: Request[AnyContent] = CSRFTokenHelper.addCSRFToken(FakeRequest().withAuthenticator(identity.loginInfo)(env))
  private val authenticator: CookieAuthenticator = FakeAuthenticator(loginInfo)(env, req)
  val userAwareReq: UserAwareRequest[CookieEnv, AnyContent] = UserAwareRequest[CookieEnv, AnyContent](Some(identity), Some(authenticator), req)
  val securedReq: SecuredRequest[CookieEnv, AnyContent] = SecuredRequest[CookieEnv, AnyContent](identity, authenticator, req)
  val messages: Messages = messagesApi.preferred(securedReq)
  val secured: SecuredReq[AnyContent] = SecuredReq[AnyContent](securedReq, messages, conf.application.env)
  val userAware: UserAwareReq[AnyContent] = UserAwareReq[AnyContent](userAwareReq, messages, conf.application.env)
  val b: Breadcrumb = Breadcrumb(Seq())

  // domain
  val timeShape: TimeShape = TimeShape.create().get
}
