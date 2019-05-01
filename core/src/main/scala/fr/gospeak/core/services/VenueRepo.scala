package fr.gospeak.core.services

import java.time.Instant

import cats.effect.IO
import fr.gospeak.core.domain.{Group, Partner, User, Venue}
import fr.gospeak.libs.scalautils.domain.{Done, Page}

trait VenueRepo extends OrgaVenueRepo

trait OrgaVenueRepo {
  def create(group: Group.Id, data: Venue.Data, by: User.Id, now: Instant): IO[Venue]

  def edit(group: Group.Id, id: Venue.Id)(data: Venue.Data, by: User.Id, now: Instant): IO[Done]

  def list(group: Group.Id, params: Page.Params): IO[Page[(Partner, Venue)]]

  def find(group: Group.Id, id: Venue.Id): IO[Option[(Partner, Venue)]]
}
