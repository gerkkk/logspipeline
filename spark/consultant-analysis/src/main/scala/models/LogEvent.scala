package models

import java.sql.Timestamp
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import scala.util.Try
import scala.util.matching.Regex

case class LogEvent(
  command: String,
  timestamp: Option[Timestamp],
  args: Map[String, String]
)

object LogEvent {
  
  private val timestampFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy_HH:mm:ss")
  private val docIdPattern: Regex = "[A-Z]+_\\d+".r
  
  def parse(line: String): Option[LogEvent] = {
    val trimmed = line.trim
    if (trimmed.isEmpty) return None
    
    val parts = trimmed.split("\\s+", 3)
    if (parts.length < 2) return None
    
    val first = parts(0)
    val second = parts(1)
    
    val isNumber = first.replaceFirst("^-", "").forall(_.isDigit)
    val isTimestamp = second.matches("\\d{2}\\.\\d{2}\\.\\d{4}_\\d{2}:\\d{2}:\\d{2}")
    
    if (isNumber && !isTimestamp) {
      return Some(LogEvent("NUMERIC_COMMAND", None, Map("data" -> trimmed)))
    }
    
    val timestamp = Try {
      val dt = LocalDateTime.parse(second, timestampFormatter)
      Timestamp.valueOf(dt)
    }.toOption
    
    val command = first
    val args = if (parts.length == 3) {
      parseArgs(command, parts(2))
    } else {
      Map.empty[String, String]
    }
    
    Some(LogEvent(command, timestamp, args))
  }
  
  private def parseArgs(command: String, rest: String): Map[String, String] = {
    command match {
      case "QS" =>
        val bracePattern = """\{([^}]*)\}\s*(.*)""".r
        rest match {
          case bracePattern(queryText, docs) =>
            Map("query_text" -> queryText, "document_ids" -> docs.trim)
          case _ =>
            Map("document_ids" -> rest)
        }
      case "DOC_OPEN" =>
        val docParts = rest.split("\\s+", 2)
        if (docParts.length == 2) {
          Map("search_id" -> docParts(0), "document_id" -> docParts(1))
        } else {
          Map("search_id" -> rest)
        }
      case _ =>
        Map("data" -> rest)
    }
  }
  
  def extractDocumentIds(str: String): Seq[String] = {
    docIdPattern.findAllIn(str).toSeq
  }
}
