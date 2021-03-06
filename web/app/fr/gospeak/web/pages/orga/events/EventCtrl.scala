package fr.gospeak.web.pages.orga.events

import cats.data.OptionT
import cats.effect.IO
import com.mohiva.play.silhouette.api.Silhouette
import fr.gospeak.core.ApplicationConf
import fr.gospeak.core.domain._
import fr.gospeak.core.services.meetup.MeetupSrv
import fr.gospeak.core.services.storage._
import fr.gospeak.infra.services.{EmailSrv, TemplateSrv}
import fr.gospeak.libs.scalautils.Extensions._
import fr.gospeak.libs.scalautils.domain.{Done, Html, Page}
import fr.gospeak.web.auth.domain.CookieEnv
import fr.gospeak.web.domain.{Breadcrumb, GospeakMessageBus, MessageBuilder}
import fr.gospeak.web.emails.Emails
import fr.gospeak.web.pages.orga.GroupCtrl
import fr.gospeak.web.pages.orga.events.EventCtrl._
import fr.gospeak.web.pages.orga.events.EventForms.PublishOptions
import fr.gospeak.web.services.EventSrv
import fr.gospeak.web.utils.{SecuredReq, UICtrl}
import play.api.data.Form
import play.api.mvc._

import scala.util.control.NonFatal

class EventCtrl(cc: ControllerComponents,
                silhouette: Silhouette[CookieEnv],
                env: ApplicationConf.Env,
                appConf: ApplicationConf,
                userRepo: OrgaUserRepo,
                groupRepo: OrgaGroupRepo,
                cfpRepo: OrgaCfpRepo,
                eventRepo: OrgaEventRepo,
                venueRepo: OrgaVenueRepo,
                proposalRepo: OrgaProposalRepo,
                groupSettingsRepo: GroupSettingsRepo,
                builder: MessageBuilder,
                templateSrv: TemplateSrv,
                eventSrv: EventSrv,
                meetupSrv: MeetupSrv,
                emailSrv: EmailSrv,
                mb: GospeakMessageBus) extends UICtrl(cc, silhouette, env) {
  def list(group: Group.Slug, params: Page.Params): Action[AnyContent] = SecuredActionIO { implicit req =>
    (for {
      groupElt <- OptionT(groupRepo.find(req.user.id, group))
      events <- OptionT.liftF(eventRepo.list(groupElt.id, params))
      cfps <- OptionT.liftF(cfpRepo.list(events.items.flatMap(_.cfp)))
      venues <- OptionT.liftF(venueRepo.listFull(groupElt.id, events.items.flatMap(_.venue)))
      proposals <- OptionT.liftF(proposalRepo.list(events.items.flatMap(_.talks)))
      speakers <- OptionT.liftF(userRepo.list(proposals.flatMap(_.users)))
      b = listBreadcrumb(groupElt)
    } yield Ok(html.list(groupElt, events, cfps, venues, proposals, speakers)(b))).value.map(_.getOrElse(groupNotFound(group)))
  }

  def create(group: Group.Slug): Action[AnyContent] = SecuredActionIO { implicit req =>
    createView(group, EventForms.create)
  }

  def doCreate(group: Group.Slug): Action[AnyContent] = SecuredActionIO { implicit req =>
    EventForms.create.bindFromRequest.fold(
      formWithErrors => createView(group, formWithErrors),
      data => (for {
        groupElt <- OptionT(groupRepo.find(req.user.id, group))
        // TODO check if slug not already exist
        eventElt <- OptionT.liftF(eventRepo.create(groupElt.id, data, req.user.id, req.now))
        _ <- OptionT.liftF(mb.publishEventCreated(groupElt, eventElt))
      } yield Redirect(routes.EventCtrl.detail(group, data.slug))).value.map(_.getOrElse(groupNotFound(group)))
    )
  }

  private def createView(group: Group.Slug, form: Form[Event.Data])(implicit req: SecuredReq[AnyContent]): IO[Result] = {
    (for {
      groupElt <- OptionT(groupRepo.find(req.user.id, group))
      meetupAccount <- OptionT.liftF(groupSettingsRepo.findMeetup(groupElt.id))
      eventDescription <- OptionT.liftF(groupSettingsRepo.findEventDescription(groupElt.id))
      b = listBreadcrumb(groupElt).add("New" -> routes.EventCtrl.create(group))
      filledForm = if (form.hasErrors) form else form.bind(Map("description.value" -> eventDescription.value)).discardingErrors
    } yield Ok(html.create(groupElt, meetupAccount.isDefined, filledForm)(b))).value.map(_.getOrElse(groupNotFound(group)))
  }

  def detail(group: Group.Slug, event: Event.Slug, params: Page.Params): Action[AnyContent] = SecuredActionIO { implicit req =>
    val customParams = params.defaultSize(40).defaultOrderBy(proposalRepo.fields.created)
    (for {
      e <- OptionT(eventSrv.getFullEvent(group, event, req.user.id))
      eventTemplates <- OptionT.liftF(groupSettingsRepo.findEventTemplates(e.group.id))
      proposals <- OptionT.liftF(e.cfpOpt.map(cfp => proposalRepo.list(cfp.id, Proposal.Status.Pending, customParams)).getOrElse(IO.pure(Page.empty[Proposal])))
      speakers <- OptionT.liftF(userRepo.list(proposals.items.flatMap(_.users).distinct))
      desc = eventSrv.buildDescription(e)
      b = breadcrumb(e.group, e.event)
      res = Ok(html.detail(e.group, e.event, e.venueOpt, e.talks, desc, e.cfpOpt, proposals, e.speakers ++ speakers, eventTemplates, EventForms.cfp, EventForms.notes.fill(e.event.orgaNotes.text))(b))
    } yield res).value.map(_.getOrElse(eventNotFound(group, event)))
  }

  def showTemplate(group: Group.Slug, event: Event.Slug, templateId: String): Action[AnyContent] = SecuredActionIO { implicit req =>
    (for {
      e <- OptionT(eventSrv.getFullEvent(group, event, req.user.id))
      eventTemplates <- OptionT.liftF(groupSettingsRepo.findEventTemplates(e.group.id))
      data = builder.buildEventInfo(e)
      res = eventTemplates.get(templateId)
        .flatMap(template => templateSrv.render(template, data).toOption)
        .map(text => Ok(html.showTemplate(Html(text))))
        .getOrElse(Redirect(routes.EventCtrl.detail(group, event)).flashing("error" -> s"Invalid template '$templateId'"))
    } yield res).value.map(_.getOrElse(eventNotFound(group, event)))
  }

  def edit(group: Group.Slug, event: Event.Slug): Action[AnyContent] = SecuredActionIO { implicit req =>
    editView(group, event, EventForms.create)
  }

  def doEdit(group: Group.Slug, event: Event.Slug): Action[AnyContent] = SecuredActionIO { implicit req =>
    EventForms.create.bindFromRequest.fold(
      formWithErrors => editView(group, event, formWithErrors),
      data => (for {
        groupElt <- OptionT(groupRepo.find(req.user.id, group))
        eventOpt <- OptionT.liftF(eventRepo.find(groupElt.id, data.slug))
        res <- OptionT.liftF(eventOpt match {
          case Some(duplicate) if data.slug != event =>
            editView(group, event, EventForms.create.fillAndValidate(data).withError("slug", s"Slug already taken by event: ${duplicate.name.value}"))
          case _ =>
            eventRepo.edit(groupElt.id, event)(data, req.user.id, req.now).map { _ => Redirect(routes.EventCtrl.detail(group, data.slug)) }
        })
      } yield res).value.map(_.getOrElse(groupNotFound(group)))
    )
  }

  private def editView(group: Group.Slug, event: Event.Slug, form: Form[Event.Data])(implicit req: SecuredReq[AnyContent]): IO[Result] = {
    (for {
      groupElt <- OptionT(groupRepo.find(req.user.id, group))
      eventElt <- OptionT(eventRepo.find(groupElt.id, event))
      meetupAccount <- OptionT.liftF(groupSettingsRepo.findMeetup(groupElt.id))
      b = breadcrumb(groupElt, eventElt).add("Edit" -> routes.EventCtrl.edit(group, event))
      filledForm = if (form.hasErrors) form else form.fill(eventElt.data)
    } yield Ok(html.edit(groupElt, meetupAccount.isDefined, eventElt, filledForm)(b))).value.map(_.getOrElse(eventNotFound(group, event)))
  }

  def doEditNotes(group: Group.Slug, event: Event.Slug): Action[AnyContent] = SecuredActionIO { implicit req =>
    EventForms.notes.bindFromRequest.fold(
      formWithErrors => IO.pure(Redirect(routes.EventCtrl.detail(group, event)).flashing("error" -> s"Unable to edit notes: ${req.formatErrors(formWithErrors)}")),
      notes => (for {
        groupElt <- OptionT(groupRepo.find(req.user.id, group))
        _ <- OptionT.liftF(eventRepo.editNotes(groupElt.id, event)(notes, req.user.id, req.now))
      } yield Redirect(routes.EventCtrl.detail(group, event))).value.map(_.getOrElse(eventNotFound(group, event)))
    )
  }

  def attachCfp(group: Group.Slug, event: Event.Slug): Action[AnyContent] = SecuredActionIO { implicit req =>
    EventForms.cfp.bindFromRequest.fold(
      formWithErrors => IO.pure(Redirect(routes.EventCtrl.detail(group, event)).flashing("error" -> s"Unable to attach CFP: ${req.formatErrors(formWithErrors)}")),
      cfp => (for {
        groupElt <- OptionT(groupRepo.find(req.user.id, group))
        cfpElt <- OptionT(cfpRepo.find(groupElt.id, cfp))
        _ <- OptionT.liftF(eventRepo.attachCfp(groupElt.id, event)(cfpElt.id, req.user.id, req.now))
      } yield Redirect(routes.EventCtrl.detail(group, event))).value.map(_.getOrElse(cfpNotFound(group, event, cfp)))
    )
  }

  def addToTalks(group: Group.Slug, event: Event.Slug, talk: Proposal.Id, params: Page.Params): Action[AnyContent] = SecuredActionIO { implicit req =>
    (for {
      groupElt <- OptionT(groupRepo.find(req.user.id, group))
      eventElt <- OptionT(eventRepo.find(groupElt.id, event))
      cfpElt <- OptionT(cfpRepo.find(eventElt.id))
      proposalElt <- OptionT(proposalRepo.find(cfpElt.slug, talk))
      _ <- OptionT.liftF(eventRepo.editTalks(groupElt.id, event)(eventElt.add(talk).talks, req.user.id, req.now))
      _ <- OptionT.liftF(proposalRepo.accept(cfpElt.slug, talk, eventElt.id, req.user.id, req.now))
      _ <- OptionT.liftF(mb.publishTalkAdded(groupElt, eventElt, cfpElt, proposalElt))
    } yield Redirect(routes.EventCtrl.detail(group, event, params))).value.map(_.getOrElse(eventNotFound(group, event)))
  }

  def cancelTalk(group: Group.Slug, event: Event.Slug, talk: Proposal.Id, params: Page.Params): Action[AnyContent] = SecuredActionIO { implicit req =>
    (for {
      groupElt <- OptionT(groupRepo.find(req.user.id, group))
      eventElt <- OptionT(eventRepo.find(groupElt.id, event))
      cfpElt <- OptionT(cfpRepo.find(eventElt.id))
      proposalElt <- OptionT(proposalRepo.find(cfpElt.slug, talk))
      _ <- OptionT.liftF(eventRepo.editTalks(groupElt.id, event)(eventElt.remove(talk).talks, req.user.id, req.now))
      _ <- OptionT.liftF(proposalRepo.cancel(cfpElt.slug, talk, eventElt.id, req.user.id, req.now))
      _ <- OptionT.liftF(mb.publishTalkRemoved(groupElt, eventElt, cfpElt, proposalElt))
    } yield Redirect(routes.EventCtrl.detail(group, event, params))).value.map(_.getOrElse(eventNotFound(group, event)))
  }

  def moveTalk(group: Group.Slug, event: Event.Slug, talk: Proposal.Id, up: Boolean, params: Page.Params): Action[AnyContent] = SecuredActionIO { implicit req =>
    (for {
      groupElt <- OptionT(groupRepo.find(req.user.id, group))
      eventElt <- OptionT(eventRepo.find(groupElt.id, event))
      _ <- OptionT.liftF(eventRepo.editTalks(groupElt.id, event)(eventElt.move(talk, up).talks, req.user.id, req.now))
    } yield Redirect(routes.EventCtrl.detail(group, event, params))).value.map(_.getOrElse(eventNotFound(group, event)))
  }

  def rejectProposal(group: Group.Slug, event: Event.Slug, talk: Proposal.Id, params: Page.Params): Action[AnyContent] = SecuredActionIO { implicit req =>
    (for {
      groupElt <- OptionT(groupRepo.find(req.user.id, group))
      eventElt <- OptionT(eventRepo.find(groupElt.id, event))
      cfpElt <- OptionT(cfpRepo.find(eventElt.id))
      _ <- OptionT.liftF(proposalRepo.reject(cfpElt.slug, talk, req.user.id, req.now))
    } yield Redirect(routes.EventCtrl.detail(group, event, params))).value.map(_.getOrElse(eventNotFound(group, event)))
  }

  def publish(group: Group.Slug, event: Event.Slug): Action[AnyContent] = SecuredActionIO { implicit req =>
    publishView(group, event, EventForms.publish.fill(PublishOptions.default))
  }

  def doPublish(group: Group.Slug, event: Event.Slug): Action[AnyContent] = SecuredActionIO { implicit req =>
    EventForms.publish.bindFromRequest.fold(
      formWithErrors => publishView(group, event, formWithErrors),
      data => (for {
        e <- OptionT(eventSrv.getFullEvent(group, event, req.user.id))
        description = eventSrv.buildDescription(e)
        meetupAccount <- OptionT.liftF(groupSettingsRepo.findMeetup(e.group.id))
        meetup <- OptionT.liftF((for {
          creds <- meetupAccount
          info <- data.meetup if info.publish
        } yield meetupSrv.publish(e.event, e.venueOpt, description, info.draft, appConf.aesKey, creds)).sequence)
        _ <- OptionT.liftF(meetup.map(_._1).filter(_ => e.event.refs.meetup.isEmpty)
          .map(ref => e.event.copy(refs = e.event.refs.copy(meetup = Some(ref))))
          .map(eventElt => eventRepo.edit(e.group.id, event)(eventElt.data, req.user.id, req.now)).sequence)
        _ <- OptionT.liftF(meetup.flatMap(m => m._2.flatMap(r => e.venueOpt.map(v => (r, v)))).filter { case (_, v) => v.refs.meetup.isEmpty }
          .map { case (ref, venue) => venueRepo.edit(e.group.id, venue.id)(venue.data.copy(refs = venue.refs.copy(meetup = Some(ref))), req.user.id, req.now) }.sequence)
        _ <- OptionT.liftF(eventRepo.publish(e.group.id, event, req.user.id, req.now))
        _ <- if (data.notifyMembers) {
          OptionT.liftF(groupRepo.listMembers(e.group.id)
            .flatMap(members => members.map(m => emailSrv.send(Emails.eventPublished(e.group, e.event, e.venueOpt, m))).sequence))
        } else {
          OptionT.liftF(IO.pure(Seq.empty[Done]))
        }
      } yield Redirect(routes.EventCtrl.detail(group, event))).value.map(_.getOrElse(eventNotFound(group, event))))
      .recover {
        case NonFatal(e) => Redirect(routes.EventCtrl.detail(group, event)).flashing("error" -> s"An error happened: ${e.getMessage}")
      }
  }

  private def publishView(group: Group.Slug, event: Event.Slug, form: Form[PublishOptions])(implicit req: SecuredReq[AnyContent]): IO[Result] = {
    (for {
      e <- OptionT(eventSrv.getFullEvent(group, event, req.user.id))
      description = eventSrv.buildDescription(e)
      meetupAccount <- OptionT.liftF(groupSettingsRepo.findMeetup(e.group.id))
      b = breadcrumb(e.group, e.event).add("Publish" -> routes.EventCtrl.publish(group, event))
      res = Ok(html.publish(e.group, e.event, description, form, meetupAccount.isDefined)(b))
    } yield res).value.map(_.getOrElse(groupNotFound(group)))
  }


  def contactRsvps(group: Group.Slug, event: Event.Slug): Action[AnyContent] = SecuredActionIO { implicit req =>
    contactRsvpsView(group, event, EventForms.contactAttendees)
  }

  def doContactRsvps(group: Group.Slug, event: Event.Slug): Action[AnyContent] = SecuredActionIO { implicit req =>
    EventForms.contactAttendees.bindFromRequest.fold(
      formWithErrors => contactRsvpsView(group, event, formWithErrors),
      data => (for {
        groupElt <- OptionT(groupRepo.find(req.user.id, group))
        eventElt <- OptionT(eventRepo.find(groupElt.id, event))
        sender <- OptionT(IO.pure(groupElt.senders(req.user).find(_.address == data.from)))
        rsvps <- OptionT.liftF(eventRepo.listRsvps(eventElt.id, data.to.answers))
        _ <- OptionT.liftF(rsvps.map(r => emailSrv.send(Emails.eventMessage(groupElt, eventElt, sender, data.subject, data.content, r))).sequence)
        next = Redirect(routes.EventCtrl.detail(group, event))
      } yield next.flashing("success" -> "Message sent to event attendees")).value.map(_.getOrElse(groupNotFound(group)))
    ).recover {
      case NonFatal(e) => Redirect(routes.EventCtrl.detail(group, event)).flashing("error" -> s"An error happened: ${e.getMessage}")
    }
  }

  private def contactRsvpsView(group: Group.Slug, event: Event.Slug, form: Form[EventForms.ContactAttendees])(implicit req: SecuredReq[AnyContent]): IO[Result] = {
    (for {
      groupElt <- OptionT(groupRepo.find(req.user.id, group))
      eventElt <- OptionT(eventRepo.find(groupElt.id, event))
      senders = groupElt.senders(req.user)
      b = breadcrumb(groupElt, eventElt).add("Contact attendees" -> routes.EventCtrl.contactRsvps(group, event))
    } yield Ok(html.contactRsvps(groupElt, eventElt, senders, form)(b))).value.map(_.getOrElse(groupNotFound(group)))
  }
}

object EventCtrl {
  def listBreadcrumb(group: Group): Breadcrumb =
    GroupCtrl.breadcrumb(group).add("Events" -> routes.EventCtrl.list(group.slug))

  def breadcrumb(group: Group, event: Event): Breadcrumb =
    listBreadcrumb(group).add(event.name.value -> routes.EventCtrl.detail(group.slug, event.slug))
}
