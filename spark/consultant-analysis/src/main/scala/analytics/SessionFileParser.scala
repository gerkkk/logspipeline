package analytics

import java.io.File
import lib.FileReading
import models.{LogLineParser, ParsedLogRecord}

object SessionFileParser {

  def parseFile(filePath: String): Seq[ParsedLogRecord] = {
    val sourceFileId = new File(filePath).getName
    val lines = FileReading.readLinesLenientUtf8(filePath)

    lines.zipWithIndex.flatMap { case (line, index) =>
      LogLineParser.parse(line, index + 1, sourceFileId)
    }
  }
}
