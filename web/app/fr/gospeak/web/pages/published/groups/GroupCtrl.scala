package fr.gospeak.web.pages.published.groups

import cats.data.OptionT
import cats.effect.IO
import com.mohiva.play.silhouette.api.Silhouette
import fr.gospeak.core.ApplicationConf
import fr.gospeak.core.domain.{Comment, Event, Group, Proposal}
import fr.gospeak.core.services.storage._
import fr.gospeak.infra.services.EmailSrv
import fr.gospeak.libs.scalautils.Extensions._
import fr.gospeak.libs.scalautils.domain.{CustomException, Done, Page}
import fr.gospeak.web.auth.domain.CookieEnv
import fr.gospeak.web.domain.Breadcrumb
import fr.gospeak.web.emails.Emails
import fr.gospeak.web.pages.published.HomeCtrl
import fr.gospeak.web.pages.published.groups.GroupCtrl._
import fr.gospeak.web.utils.{GenericForm, UICtrl, UserAwareReq}
import play.api.data.Form
import play.api.mvc._

import scala.util.control.NonFatal

class GroupCtrl(cc: ControllerComponents,
                silhouette: Silhouette[CookieEnv],
                env: ApplicationConf.Env,
                userRepo: PublicUserRepo,
                groupRepo: PublicGroupRepo,
                cfpRepo: PublicCfpRepo,
                eventRepo: PublicEventRepo,
                proposalRepo: PublicProposalRepo,
                sponsorRepo: PublicSponsorRepo,
                sponsorPackRepo: PublicSponsorPackRepo,
                commentRepo: PublicCommentRepo,
                emailSrv: EmailSrv) extends UICtrl(cc, silhouette, env) {
  def list(params: Page.Params): Action[AnyContent] = UserAwareActionIO { implicit req =>
    for {
      groups <- groupRepo.list(params)
      b = listBreadcrumb()
    } yield Ok(html.list(groups)(b))
  }

  def detail(group: Group.Slug): Action[AnyContent] = UserAwareActionIO { implicit req =>
    (for {
      groupElt <- OptionT(groupRepo.find(group))
      cfps <- OptionT.liftF(cfpRepo.listAllOpen(groupElt.id, req.now))
      events <- OptionT.liftF(eventRepo.listPublished(groupElt.id, Page.Params.defaults))
      sponsors <- OptionT.liftF(sponsorRepo.listCurrentFull(groupElt.id, req.now))
      packs <- OptionT.liftF(sponsorPackRepo.listActives(groupElt.id))
      orgas <- OptionT.liftF(userRepo.list(groupElt.owners.toList))
      userMembership <- OptionT.liftF(req.user.map(_.id).map(groupRepo.findActiveMember(groupElt.id, _)).sequence.map(_.flatten))
      b = breadcrumb(groupElt)
      res = Ok(html.detail(groupElt, cfps, events, sponsors, packs, orgas, userMembership)(b))
    } yield res).value.map(_.getOrElse(publicGroupNotFound(group)))
  }

  def doJoin(group: Group.Slug): Action[AnyContent] = SecuredActionIO { implicit req =>
    (for {
      groupElt <- OptionT(groupRepo.find(group))
      userMembership <- OptionT.liftF(groupRepo.findActiveMember(groupElt.id, req.user.id))
      msg <- OptionT.liftF(userMembership.swap.map(_ => groupRepo.join(groupElt.id)(req.user, req.now)
        .map(_ => "success" -> s"You are now a member of <b>${groupElt.name.value}</b>")
        .recover { case NonFatal(e) => "error" -> s"Can't join <b>${groupElt.name.value}</b>: ${e.getMessage}" })
        .getOrElse(IO.pure("success" -> s"You are already a member of <b>${groupElt.name.value}</b>")))
      next = Redirect(routes.GroupCtrl.detail(group)).flashing(msg)
    } yield next).value.map(_.getOrElse(publicGroupNotFound(group)))
  }

  def doLeave(group: Group.Slug): Action[AnyContent] = SecuredActionIO { implicit req =>
    (for {
      groupElt <- OptionT(groupRepo.find(group))
      userMembership <- OptionT.liftF(groupRepo.findActiveMember(groupElt.id, req.user.id))
      msg <- OptionT.liftF(userMembership.map(groupRepo.leave(_)(req.user.id, req.now)
        .map(_ => "success" -> s"You have leaved <b>${groupElt.name.value}</b>")
        .recover { case NonFatal(e) => "error" -> s"Can't leave <b>${groupElt.name.value}</b>, error: ${e.getMessage}" })
        .getOrElse(IO.pure("error" -> s"You are not a member of <b>${groupElt.name.value}</b>")))
      next = Redirect(routes.GroupCtrl.detail(group)).flashing(msg)
    } yield next).value.map(_.getOrElse(publicGroupNotFound(group)))
  }

  def events(group: Group.Slug, params: Page.Params): Action[AnyContent] = UserAwareActionIO { implicit req =>
    (for {
      groupElt <- OptionT(groupRepo.find(group))
      events <- OptionT.liftF(eventRepo.listPublished(groupElt.id, params))
      b = breadcrumbEvents(groupElt)
      res = Ok(html.events(groupElt, events)(b))
    } yield res).value.map(_.getOrElse(publicGroupNotFound(group)))
  }

  def event(group: Group.Slug, event: Event.Slug): Action[AnyContent] = UserAwareActionIO { implicit req =>
    eventView(group, event, GenericForm.comment)
  }

  def doSendComment(group: Group.Slug, event: Event.Slug): Action[AnyContent] = SecuredActionIO { implicit req =>
    GenericForm.comment.bindFromRequest.fold(
      formWithErrors => eventView(group, event, formWithErrors)(req.userAware),
      data => (for {
        groupElt <- OptionT(groupRepo.find(group))
        eventElt <- OptionT(eventRepo.findPublished(groupElt.id, event))
        _ <- OptionT.liftF(commentRepo.addComment(eventElt.id, data, req.user.id, req.now))
      } yield Redirect(routes.GroupCtrl.event(group, event))).value.map(_.getOrElse(publicEventNotFound(group, event)))
    )
  }

  private def eventView(group: Group.Slug, event: Event.Slug, commentForm: Form[Comment.Data])(implicit req: UserAwareReq[AnyContent]): IO[Result] = {
    (for {
      groupElt <- OptionT(groupRepo.find(group))
      eventElt <- OptionT(eventRepo.findPublished(groupElt.id, event))
      proposals <- OptionT.liftF(proposalRepo.listPublicFull(eventElt.talks))
      speakers <- OptionT.liftF(userRepo.list(proposals.flatMap(_.speakers.toList).distinct))
      comments <- OptionT.liftF(commentRepo.getComments(eventElt.id))
      yesRsvp <- OptionT.liftF(eventRepo.countYesRsvp(eventElt.id))
      userRsvp <- OptionT.liftF(req.user.map(_.id).map(eventRepo.findRsvp(eventElt.id, _)).sequence.map(_.flatten))
      b = breadcrumbEvent(groupElt, eventElt)
      res = Ok(html.event(groupElt, eventElt, proposals, speakers, comments, commentForm, yesRsvp, userRsvp)(b))
    } yield res).value.map(_.getOrElse(publicEventNotFound(group, event)))
  }

  def doRsvp(group: Group.Slug, event: Event.Slug, answer: Event.Rsvp.Answer): Action[AnyContent] = SecuredActionIO { implicit req =>
    import Event.Rsvp.Answer.{No, Wait, Yes}
    val waitMsg = "warning" -> "Thanks but this event is already full. You are on waiting list, your place will be reserved as soon as there is more places"
    val yesMsg = "success" -> "You seat is booked!"
    val noMsg = "success" -> "Thanks for answering, see you an other time"
    (for {
      groupElt <- OptionT(groupRepo.find(group))
      eventElt <- OptionT(eventRepo.findPublished(groupElt.id, event))
      _ <- OptionT.liftF(if (eventElt.canRsvp(req.now)) IO.pure(Done) else IO.raiseError(CustomException("Can't RSVP for now")))
      yesRsvp <- OptionT.liftF(eventRepo.countYesRsvp(eventElt.id))
      userMembership <- OptionT.liftF(groupRepo.findActiveMember(groupElt.id, req.user.id))
      _ <- OptionT.liftF(userMembership.map(_ => IO.pure(Done)).getOrElse(groupRepo.join(groupElt.id)(req.user, req.now)))
      userRsvp <- OptionT.liftF(eventRepo.findRsvp(eventElt.id, req.user.id))
      msg <- OptionT.liftF((userRsvp.map(_.answer), answer) match {
        case (_, Wait) => IO.pure("error" -> "Can't answer Wait on Rsvp")
        case (None, No) => eventRepo.createRsvp(eventElt.id, No)(req.user, req.now).map(_ => noMsg)
        case (None, Yes) =>
          if (eventElt.isFull(yesRsvp)) eventRepo.createRsvp(eventElt.id, Wait)(req.user, req.now).map(_ => waitMsg)
          else eventRepo.createRsvp(eventElt.id, Yes)(req.user, req.now).map(_ => yesMsg)
        case (Some(Yes), Yes) | (Some(Wait), Yes) | (Some(No), No) => IO.pure("success" -> "Thanks")
        case (Some(Yes), No) => for {
          _ <- eventRepo.editRsvp(eventElt.id, No)(req.user, req.now)
          firstWait <- eventRepo.findFirstWait(eventElt.id)
          _ <- firstWait.map { r =>
            eventRepo.editRsvp(eventElt.id, Yes)(r.user, req.now)
              .flatMap(_ => emailSrv.send(Emails.movedFromWaitingListToAttendees(groupElt, eventElt.event, r.user)))
          }.getOrElse(IO.pure(Done))
        } yield noMsg
        case (Some(Wait), No) => eventRepo.editRsvp(eventElt.id, No)(req.user, req.now).map(_ => noMsg)
        case (Some(No), Yes) =>
          if (eventElt.isFull(yesRsvp)) eventRepo.editRsvp(eventElt.id, Wait)(req.user, req.now).map(_ => waitMsg)
          else eventRepo.editRsvp(eventElt.id, Yes)(req.user, req.now).map(_ => yesMsg)
      })
      next = Redirect(routes.GroupCtrl.event(group, event)).flashing(msg)
    } yield next).value.map(_.getOrElse(publicGroupNotFound(group))).recover {
      case e: CustomException => Redirect(routes.GroupCtrl.event(group, event)).flashing("error" -> e.message)
      case NonFatal(e) => Redirect(routes.GroupCtrl.event(group, event)).flashing("error" -> s"Unexpected error: ${e.getMessage}")
    }
  }

  def talks(group: Group.Slug, params: Page.Params): Action[AnyContent] = UserAwareActionIO { implicit req =>
    (for {
      groupElt <- OptionT(groupRepo.find(group))
      proposals <- OptionT.liftF(proposalRepo.listPublicFull(groupElt.id, params.defaultOrderBy("title")))
      speakers <- OptionT.liftF(userRepo.list(proposals.items.flatMap(_.speakers.toList).distinct))
      b = breadcrumbTalks(groupElt)
      res = Ok(html.talks(groupElt, proposals, speakers)(b))
    } yield res).value.map(_.getOrElse(publicGroupNotFound(group)))
  }

  def talk(group: Group.Slug, proposal: Proposal.Id): Action[AnyContent] = UserAwareActionIO { implicit req =>
    (for {
      groupElt <- OptionT(groupRepo.find(group))
      proposalElt <- OptionT(proposalRepo.findPublicFull(groupElt.id, proposal))
      speakers <- OptionT.liftF(userRepo.list(proposalElt.speakers.toList))
      b = breadcrumbTalk(groupElt, proposalElt)
      res = Ok(html.talk(groupElt, proposalElt, speakers)(b))
    } yield res).value.map(_.getOrElse(publicProposalNotFound(group, proposal)))
  }

  def speakers(group: Group.Slug, params: Page.Params): Action[AnyContent] = UserAwareActionIO { implicit req =>
    (for {
      groupElt <- OptionT(groupRepo.find(group))
      speakers <- OptionT.liftF(userRepo.speakers(groupElt.id, params))
      b = breadcrumbSpeakers(groupElt)
      res = Ok(html.speakers(groupElt, speakers)(b))
    } yield res).value.map(_.getOrElse(publicGroupNotFound(group)))
  }

  def members(group: Group.Slug, params: Page.Params): Action[AnyContent] = UserAwareActionIO { implicit req =>
    (for {
      groupElt <- OptionT(groupRepo.find(group))
      members <- OptionT.liftF(groupRepo.listMembers(groupElt.id, params))
      b = breadcrumbMembers(groupElt)
      res = Ok(html.members(groupElt, members)(b))
    } yield res).value.map(_.getOrElse(publicGroupNotFound(group)))
  }
}

object GroupCtrl {
  def listBreadcrumb(): Breadcrumb =
    HomeCtrl.breadcrumb().add("Groups" -> routes.GroupCtrl.list())

  def breadcrumb(group: Group): Breadcrumb =
    listBreadcrumb().add(group.name.value -> routes.GroupCtrl.detail(group.slug))

  def breadcrumbEvents(group: Group): Breadcrumb =
    breadcrumb(group).add("Events" -> routes.GroupCtrl.events(group.slug))

  def breadcrumbEvent(group: Group, event: Event.Full): Breadcrumb =
    breadcrumbEvents(group).add(event.name.value -> routes.GroupCtrl.event(group.slug, event.slug))

  def breadcrumbTalks(group: Group): Breadcrumb =
    breadcrumb(group).add("Talks" -> routes.GroupCtrl.talks(group.slug))

  def breadcrumbTalk(group: Group, proposal: Proposal.Full): Breadcrumb =
    breadcrumbTalks(group).add(proposal.title.value -> routes.GroupCtrl.talk(group.slug, proposal.id))

  def breadcrumbSpeakers(group: Group): Breadcrumb =
    breadcrumb(group).add("Speakers" -> routes.GroupCtrl.speakers(group.slug))

  def breadcrumbMembers(group: Group): Breadcrumb =
    breadcrumb(group).add("Members" -> routes.GroupCtrl.members(group.slug))
}
