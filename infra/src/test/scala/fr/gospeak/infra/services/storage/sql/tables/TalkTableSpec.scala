package fr.gospeak.infra.services.storage.sql.tables

import cats.data.NonEmptyList
import fr.gospeak.core.domain.Talk
import fr.gospeak.core.domain.utils.Info
import fr.gospeak.infra.services.storage.sql.tables.TalkTable._
import fr.gospeak.infra.services.storage.sql.tables.testingutils.TableSpec

class TalkTableSpec extends TableSpec {
  private val slug = Talk.Slug("my-talk")
  private val talk = Talk(talkId, slug, Talk.Title("My Talk"), "best talk", NonEmptyList.of(userId), Info(userId))

  describe("TalkTable") {
    describe("insert") {
      it("should generate the query") {
        val q = insert(talk)
        q.sql shouldBe "INSERT INTO talks (id, slug, title, description, speakers, created, created_by, updated, updated_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
        check(q)
      }
    }
    describe("selectOne") {
      it("should generate the query") {
        val q = selectOne(userId, slug)
        q.sql shouldBe "SELECT id, slug, title, description, speakers, created, created_by, updated, updated_by FROM talks WHERE speakers LIKE ? AND slug=?"
        check(q)
      }
    }
    describe("selectPage") {
      it("should generate the query") {
        val (s, c) = selectPage(userId, params)
        s.sql shouldBe "SELECT id, slug, title, description, speakers, created, created_by, updated, updated_by FROM talks WHERE speakers LIKE ? ORDER BY title OFFSET 0 LIMIT 20"
        c.sql shouldBe "SELECT count(*) FROM talks WHERE speakers LIKE ? "
        check(s)
        check(c)
      }
    }
  }
}
