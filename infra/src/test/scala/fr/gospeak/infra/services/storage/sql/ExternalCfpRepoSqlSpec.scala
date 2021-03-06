package fr.gospeak.infra.services.storage.sql

import fr.gospeak.infra.services.storage.sql.ExternalCfpRepoSqlSpec._
import fr.gospeak.infra.services.storage.sql.testingutils.RepoSpec
import fr.gospeak.infra.services.storage.sql.testingutils.RepoSpec.mapFields

class ExternalCfpRepoSqlSpec extends RepoSpec {
  describe("ExternalCfpRepoSql") {
    describe("Queries") {
      it("should build insert") {
        val q = ExternalCfpRepoSql.insert(externalCfp)
        check(q, s"INSERT INTO ${table.stripSuffix(" ec")} (${mapFields(fieldsInsert, _.stripPrefix("ec."))}) VALUES (${mapFields(fieldsInsert, _ => "?")})")
      }
      it("should build update") {
        val q = ExternalCfpRepoSql.update(externalCfp.id)(externalCfp.data, user.id, now)
        check(q, s"UPDATE $table SET name=?, logo=?, description=?, begin=?, close=?, url=?, event_start=?, event_finish=?, event_url=?, location=?, location_lat=?, location_lng=?, location_locality=?, location_country=?, tickets_url=?, videos_url=?, twitter_account=?, twitter_hashtag=?, tags=?, updated_at=?, updated_by=? WHERE id=?")
      }
      it("should build selectOne for cfp id") {
        val q = ExternalCfpRepoSql.selectOne(externalCfp.id)
        check(q, s"SELECT $fields FROM $table WHERE ec.id=? $orderBy")
      }
      it("should build selectCommonPage") {
        val q = ExternalCfpRepoSql.selectCommonPage(now, params)
        val req = s"SELECT $commonFields FROM $commonTable " +
          s"WHERE (c.begin IS NULL OR c.begin < ?) AND (c.close IS NULL OR c.close > ?) " +
          s"ORDER BY c.close IS NULL, c.close, c.name IS NULL, c.name " +
          s"LIMIT 20 OFFSET 0"
        q.fr.query.sql shouldBe req
        // check(q, req) // not null types become nullable when doing union, so it fails :(
      }
      it("should build selectTags") {
        val q = ExternalCfpRepoSql.selectTags()
        check(q, s"SELECT ec.tags FROM $table")
      }
    }
  }
}

object ExternalCfpRepoSqlSpec {
  val table = "external_cfps ec"
  val fieldsInsert: String = mapFields("id, name, logo, description, begin, close, url, event_start, event_finish, event_url, location, location_lat, location_lng, location_locality, location_country, tickets_url, videos_url, twitter_account, twitter_hashtag, tags, created_at, created_by, updated_at, updated_by", "ec." + _)
  val fields: String = fieldsInsert.split(", ").filterNot(_.startsWith("ec.location_")).mkString(", ")
  val orderBy = "ORDER BY ec.close IS NULL, ec.close, ec.name IS NULL, ec.name"

  val commonTable: String = "(" +
    "(SELECT c.id,       c.slug, c.name, null as logo, c.begin, c.close, g.location, c.description, c.tags FROM cfps c INNER JOIN groups g ON c.group_id=g.id) UNION " +
    "(SELECT c.id, null as slug, c.name,       c.logo, c.begin, c.close, c.location, c.description, c.tags FROM external_cfps c)) c"
  val commonFields = "c.id, c.slug, c.name, c.logo, c.begin, c.close, c.location, c.description, c.tags"
}
