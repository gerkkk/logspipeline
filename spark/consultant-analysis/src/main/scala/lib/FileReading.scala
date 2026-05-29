package lib

import java.io.{BufferedReader, InputStreamReader}
import java.nio.charset.{CodingErrorAction, StandardCharsets}
import java.nio.file.{Files, Path}

object FileReading {

  private val Utf8Lenient = StandardCharsets.UTF_8
    .newDecoder()
    .onMalformedInput(CodingErrorAction.REPLACE)
    .onUnmappableCharacter(CodingErrorAction.REPLACE)

  def readLinesLenientUtf8(filePath: String): Seq[String] = {
    val path = Path.of(filePath)
    val reader = new BufferedReader(
      new InputStreamReader(Files.newInputStream(path), Utf8Lenient)
    )
    try {
      Iterator
        .continually(reader.readLine())
        .takeWhile(_ != null)
        .toSeq
    } finally {
      reader.close()
    }
  }
}
