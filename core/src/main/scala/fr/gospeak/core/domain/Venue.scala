package fr.gospeak.core.domain

import java.time.ZoneId

import fr.gospeak.core.domain.utils.Info
import fr.gospeak.core.services.meetup.domain.MeetupVenue
import fr.gospeak.libs.scalautils.domain._

final case class Venue(id: Venue.Id,
                       partner: Partner.Id,
                       contact: Option[Contact.Id],
                       address: GMapPlace,
                       description: Markdown,
                       roomSize: Option[Int],
                       refs: Venue.ExtRefs,
                       info: Info) {
  def data: Venue.Data = Venue.Data(this)

  def users: Seq[User.Id] = info.users
}

object Venue {
  def apply(group: Group.Id, data: Data, info: Info): Venue =
    new Venue(Id.generate(), data.partner, data.contact, data.address, data.description, data.roomSize, ExtRefs(), info)

  final class Id private(value: String) extends DataClass(value) with IId

  object Id extends UuidIdBuilder[Id]("Venue.Id", new Id(_))

  final case class ExtRefs(meetup: Option[MeetupVenue.Ref] = None)

  final case class Full(venue: Venue, partner: Partner, contact: Option[Contact]) {
    def users: Seq[User.Id] = (venue.users ++ partner.users ++ contact.map(_.users).getOrElse(Seq())).distinct

    def id: Id = venue.id

    def address: GMapPlace = venue.address

    def description: Markdown = venue.description

    def roomSize: Option[Int] = venue.roomSize

    def timezone: ZoneId = venue.address.timezone

    def refs: ExtRefs = venue.refs

    def data: Data = venue.data

    def info: Info = venue.info
  }

  final case class Data(partner: Partner.Id,
                        contact: Option[Contact.Id],
                        address: GMapPlace,
                        description: Markdown,
                        roomSize: Option[Int],
                        refs: Venue.ExtRefs)

  object Data {
    def apply(venue: Venue): Data = new Data(venue.partner, venue.contact, venue.address, venue.description, venue.roomSize, venue.refs)
  }

}
