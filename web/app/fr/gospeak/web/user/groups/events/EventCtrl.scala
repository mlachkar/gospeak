package fr.gospeak.web.user.groups.events

import cats.data.OptionT
import cats.effect.IO
import fr.gospeak.core.domain.utils.Page
import fr.gospeak.core.domain.{Event, Group, User}
import fr.gospeak.core.services.GospeakDb
import fr.gospeak.web.domain.{Breadcrumb, HeaderInfo, NavLink}
import fr.gospeak.web.user.groups.GroupCtrl
import fr.gospeak.web.user.groups.events.EventCtrl._
import play.api.data.Form
import play.api.i18n._
import play.api.mvc._

class EventCtrl(cc: ControllerComponents, db: GospeakDb) extends AbstractController(cc) with I18nSupport {
  def list(group: Group.Slug, params: Page.Params): Action[AnyContent] = Action.async { implicit req: Request[AnyContent] =>
    implicit val user: User = db.authed() // logged user
    (for {
      groupElt <- OptionT(db.getGroup(user.id, group))
      events <- OptionT.liftF(db.getEvents(groupElt.id, params))
      h = listHeader(group)
      b = listBreadcrumb(user.name, group -> groupElt.name)
    } yield Ok(html.list(groupElt, events)(h, b))).value.map(_.getOrElse(NotFound)).unsafeToFuture()
  }

  def create(group: Group.Slug): Action[AnyContent] = Action.async { implicit req: Request[AnyContent] =>
    implicit val user: User = db.authed() // logged user
    createForm(group, EventForms.create).unsafeToFuture()
  }

  def doCreate(group: Group.Slug): Action[AnyContent] = Action.async { implicit req: Request[AnyContent] =>
    implicit val user: User = db.authed() // logged user
    EventForms.create.bindFromRequest.fold(
      formWithErrors => createForm(group, formWithErrors),
      data => {
        (for {
          groupElt <- OptionT(db.getGroup(user.id, group))
          // TODO check if slug not already exist
          _ <- OptionT.liftF(db.createEvent(groupElt.id, data.slug, data.name, user.id))
        } yield Redirect(routes.EventCtrl.detail(group, data.slug))).value.map(_.getOrElse(NotFound))
      }
    ).unsafeToFuture()
  }

  private def createForm(group: Group.Slug, form: Form[EventForms.Create])(implicit req: Request[AnyContent], user: User): IO[Result] = {
    (for {
      groupElt <- OptionT(db.getGroup(user.id, group))
      h = header(group)
      b = listBreadcrumb(user.name, group -> groupElt.name).add("New" -> routes.EventCtrl.create(group))
    } yield Ok(html.create(groupElt, form)(h, b))).value.map(_.getOrElse(NotFound))
  }

  def detail(group: Group.Slug, event: Event.Slug): Action[AnyContent] = Action.async { implicit req: Request[AnyContent] =>
    implicit val user: User = db.authed() // logged user
    (for {
      groupElt <- OptionT(db.getGroup(user.id, group))
      eventElt <- OptionT(db.getEvent(groupElt.id, event))
      h = header(group)
      b = breadcrumb(user.name, group -> groupElt.name, event -> eventElt.name)
    } yield Ok(html.detail(groupElt, eventElt)(h, b))).value.map(_.getOrElse(NotFound)).unsafeToFuture()
  }
}

object EventCtrl {
  def listHeader(group: Group.Slug): HeaderInfo =
    GroupCtrl.header(group)
      .copy(brand = NavLink("Gospeak", fr.gospeak.web.user.groups.routes.GroupCtrl.detail(group)))
      .activeFor(routes.EventCtrl.list(group))

  def listBreadcrumb(user: User.Name, group: (Group.Slug, Group.Name)): Breadcrumb =
    group match {
      case (groupSlug, _) => GroupCtrl.breadcrumb(user, group).add("Events" -> routes.EventCtrl.list(groupSlug))
    }

  def header(group: Group.Slug): HeaderInfo =
    listHeader(group)

  def breadcrumb(user: User.Name, group: (Group.Slug, Group.Name), event: (Event.Slug, Event.Name)): Breadcrumb =
    (group, event) match {
      case ((groupSlug, _), (eventSlug, eventName)) =>
        listBreadcrumb(user, group).add(eventName.value -> routes.EventCtrl.detail(groupSlug, eventSlug))
    }
}
