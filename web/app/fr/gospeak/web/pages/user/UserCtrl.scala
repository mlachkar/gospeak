package fr.gospeak.web.pages.user

import com.mohiva.play.silhouette.api.Silhouette
import com.mohiva.play.silhouette.api.actions.SecuredRequest
import fr.gospeak.core.domain.User
import fr.gospeak.core.services.{UserGroupRepo, UserTalkRepo}
import fr.gospeak.libs.scalautils.domain.Page
import fr.gospeak.web.auth.domain.CookieEnv
import fr.gospeak.web.domain._
import fr.gospeak.web.pages
import fr.gospeak.web.pages.published.HomeCtrl
import fr.gospeak.web.pages.user.UserCtrl._
import fr.gospeak.web.utils.UICtrl
import play.api.mvc._

class UserCtrl(cc: ControllerComponents,
               silhouette: Silhouette[CookieEnv],
               groupRepo: UserGroupRepo,
               talkRepo: UserTalkRepo) extends UICtrl(cc, silhouette) {

  import silhouette._

  def index(): Action[AnyContent] = SecuredAction.async { implicit req =>
    implicit val user: User = req.identity.user
    (for {
      groups <- groupRepo.list(user.id, Page.Params.defaults)
      talks <- talkRepo.list(user.id, Page.Params.defaults)
    } yield Ok(html.index(groups, talks)(indexHeader(), breadcrumb(user.name)))).unsafeToFuture()
  }

  def profile(): Action[AnyContent] = SecuredAction { implicit req =>
    val h = header().activeFor(routes.UserCtrl.profile())
    val b = breadcrumb(req.identity.user.name).add("Profile" -> routes.UserCtrl.profile())
    Ok(html.profile()(h, b))
  }
}

object UserCtrl {
  val userNav: Seq[NavLink] = Seq(
    NavLink("Groups", pages.orga.routes.GroupCtrl.list()),
    NavLink("Talks", pages.speaker.routes.TalkCtrl.list()))

  private val leftNav = NavDropdown("Public", HomeCtrl.publicNav) +: userNav

  def rightNav()(implicit req: SecuredRequest[CookieEnv, AnyContent]): Seq[NavMenu] =
    Seq(NavDropdown(
      s"""<img src="${req.identity.user.avatar.url.value}" class="avatar">""", Seq(
        NavLink("Profile", routes.UserCtrl.profile()),
        NavLink("logout", fr.gospeak.web.auth.routes.AuthCtrl.doLogout()))))

  def indexHeader()(implicit req: SecuredRequest[CookieEnv, AnyContent]): HeaderInfo = HeaderInfo(
    brand = NavLink("Gospeak", fr.gospeak.web.pages.published.routes.HomeCtrl.index()),
    links = leftNav,
    rightLinks = rightNav())

  def header()(implicit req: SecuredRequest[CookieEnv, AnyContent]): HeaderInfo =
    indexHeader().copy(brand = NavLink("Gospeak", routes.UserCtrl.index()))

  def breadcrumb(user: User.Name) = Breadcrumb(Seq(
    BreadcrumbLink("Public", fr.gospeak.web.pages.published.routes.HomeCtrl.index()),
    BreadcrumbLink(user.value, routes.UserCtrl.index())))
}