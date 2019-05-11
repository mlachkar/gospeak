package fr.gospeak.infra.services.slack

import cats.data.EitherT
import cats.effect.IO
import fr.gospeak.core.domain.utils.TemplateData
import fr.gospeak.core.services.slack.SlackSrv
import fr.gospeak.core.services.slack.domain.SlackAction.PostMessage
import fr.gospeak.core.services.slack.domain._
import fr.gospeak.infra.libs.slack.{SlackClient, domain => api}
import fr.gospeak.infra.services.TemplateSrv
import fr.gospeak.infra.services.slack.SlackSrvImpl._
import fr.gospeak.libs.scalautils.Extensions._
import fr.gospeak.libs.scalautils.domain.CustomException

// SlackSrv should not use Slack classes in the API, it's independent and the implementation should do the needed conversion
class SlackSrvImpl(client: SlackClient,
                   templateSrv: TemplateSrv) extends SlackSrv {
  override def getInfos(token: SlackToken): IO[SlackTokenInfo] =
    client.info(toSlack(token)).map(_.map(toGospeak)).flatMap(toIO)

  override def exec(creds: SlackCredentials, action: SlackAction, data: TemplateData): IO[Unit] = action match {
    case a: PostMessage => postMessage(creds, a, data).map(_ => ())
  }

  private def postMessage(creds: SlackCredentials, action: PostMessage, data: TemplateData): IO[Either[api.SlackError, api.SlackMessage]] = {
    val token = toSlack(creds.token)
    val sender = api.SlackSender.Bot(creds.name, creds.avatar.map(_.value))
    for {
      channel <- templateSrv.render(action.channel, data).map(api.SlackChannel.Name).toIO(CustomException(_))
      message <- templateSrv.render(action.message, data).map(api.SlackContent.Markdown).toIO(CustomException(_))
      attempt1 <- client.postMessage(token, sender, channel, message)
      attempt2 <- attempt1 match {
        case Left(api.SlackError(false, "channel_not_found", _, _)) if action.createdChannelIfNotExist =>
          (for {
            createdChannel <- EitherT(client.createChannel(token, channel))
            invitedUsers <- EitherT.liftF(if (action.inviteEverybody) {
              client.listUsers(token).flatMap(_.getOrElse(Seq()).map(_.id).map(client.inviteToChannel(token, createdChannel.id, _)).sequence)
            } else {
              IO.pure(Seq())
            })
            attempt2 <- EitherT(client.postMessage(token, sender, createdChannel.id, message))
          } yield attempt2).value
        case _ => IO.pure(attempt1)
      }
    } yield attempt2
  }

  private def toSlack(token: SlackToken): api.SlackToken =
    api.SlackToken(token.value)

  private def toGospeak(id: api.SlackUser.Id): SlackUser.Id =
    SlackUser.Id(id.value)

  private def toGospeak(info: api.SlackTokenInfo): SlackTokenInfo =
    SlackTokenInfo(SlackTeam.Id(info.team_id.value), info.team, info.url, toGospeak(info.user_id), info.user)

  private def toIO[A](e: Either[api.SlackError, A]): IO[A] =
    e.toIO(e => CustomException(format(e)))
}

object SlackSrvImpl {
  private[slack] def format(err: api.SlackError): String =
    err.error +
      err.needed.map(" " + _).getOrElse("") +
      err.provided.map(" (provided: " + _ + ")").getOrElse("")
}
