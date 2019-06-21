package fr.gospeak.libs.scalautils.domain

object Values {

  // cf db field limit in `V1__initial_schema.sql`
  object maxLength {
    val title = 120
    val text = 4096
    val url = 1024
  }

}
