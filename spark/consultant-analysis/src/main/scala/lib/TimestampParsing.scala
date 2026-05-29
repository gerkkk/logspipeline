package lib

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import scala.util.Try

object TimestampParsing {

  private val Standard =
    """^(\d{2})\.(\d{2})\.(\d{4})_\d{2}:\d{2}:\d{2}$""".r

  private val Http =
    """^(Mon|Tue|Wed|Thu|Fri|Sat|Sun),_(\d{1,2})_([A-Za-z]{3})_(\d{4})_\d{2}:\d{2}:\d{2}_\+\d{4}$""".r

  private val IsoOut = DateTimeFormatter.ISO_LOCAL_DATE

  private val HttpMonth = Map(
    "Jan" -> 1, "Feb" -> 2, "Mar" -> 3, "Apr" -> 4, "May" -> 5, "Jun" -> 6,
    "Jul" -> 7, "Aug" -> 8, "Sep" -> 9, "Oct" -> 10, "Nov" -> 11, "Dec" -> 12
  )

  def isTimestamp(token: String): Boolean =
    token match {
      case Standard(_, _, _) | Http(_, _, _, _) => true
      case _                                    => false
    }

  def extractDate(timestamp: String): Option[String] =
    timestamp match {
      case Standard(day, month, year) =>
        Try(LocalDate.of(year.toInt, month.toInt, day.toInt).format(IsoOut)).toOption
      case Http(_, day, mon, year) =>
        HttpMonth.get(mon).flatMap { m =>
          Try(LocalDate.of(year.toInt, m, day.toInt).format(IsoOut)).toOption
        }
      case _ => None
    }
}
