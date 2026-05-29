package lib

import scala.util.matching.Regex

object LogPatterns {

  val DocId: Regex = "[A-Z]+_\\d+".r
  val SearchId: Regex = "^-?\\d+$".r
  val CardQuery: Regex = "^\\$\\d+".r
  val QsBrace: Regex = """\{([^}]*)\}\s*(.*)""".r
  val NumericFileName: Regex = """\d+""".r

  def findDocumentIds(text: String): Seq[String] =
    DocId.findAllIn(text).toSeq
}
