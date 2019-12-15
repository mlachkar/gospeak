package fr.gospeak.core.services.upload

import fr.gospeak.core.domain.utils.Creds

sealed trait UploadConf

object UploadConf {

  final case class Url() extends UploadConf

  // with use signed upload if `creds` are present, unsigned one otherwise
  final case class Cloudinary(cloudName: String,
                              uploadPreset: Option[String],
                              creds: Option[Creds]) extends UploadConf

}
