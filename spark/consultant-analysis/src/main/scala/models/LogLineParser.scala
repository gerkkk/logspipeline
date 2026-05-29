package models

import lib.{LogPatterns, TimestampParsing}

// Парсинг одной строки лога
object LogLineParser {

  def parse(line: String, lineNumber: Int, sourceFileId: String): Option[ParsedLogRecord] = {
    val raw = line.trim
    if (raw.isEmpty) return None

    val parts = raw.split("\\s+")
    val first = parts(0)

    parseSearchResults(raw, first, lineNumber, sourceFileId)
      .orElse(parseCardQuery(raw, first, lineNumber, sourceFileId))
      .orElse(parseKnownCommand(raw, parts, first, lineNumber, sourceFileId))
      .orElse {
        // известная команда без записи (SESSION_END, CARD_SEARCH_END) — не ошибка парсинга
        if (RecordType.KnownCommands.contains(first)) None
        else Some(unparseable(raw, sourceFileId, lineNumber, s"unknown token: $first"))
      }
  }

  private def unparseable(
      raw: String,
      sourceFileId: String,
      lineNumber: Int,
      error: String
  ): ParsedLogRecord =
    ParsedLogRecord(
      recordType = RecordType.Unparseable,
      sourceFileId = sourceFileId,
      lineNumber = lineNumber,
      parseError = Some(error),
      rawLine = Some(raw)
    )

  private def record(
      recordType: String,
      sourceFileId: String,
      lineNumber: Int,
      timestamp: Option[String] = None,
      documentId: Option[String] = None,
      documentIds: Seq[String] = Seq.empty,
      queryText: Option[String] = None
  ): ParsedLogRecord =
    ParsedLogRecord(
      recordType = recordType,
      sourceFileId = sourceFileId,
      lineNumber = lineNumber,
      timestamp = timestamp,
      documentId = documentId,
      documentIds = documentIds,
      queryText = queryText
    )

  private def parseSearchResults(
      raw: String,
      first: String,
      lineNumber: Int,
      sourceFileId: String
  ): Option[ParsedLogRecord] = {
    if (!LogPatterns.SearchId.matches(first)) return None
    val parts = raw.split("\\s+")
    val second = if (parts.length > 1) parts(1) else ""
    if (parts.length > 1 && TimestampParsing.isTimestamp(second)) return None

    Some(
      record(
        RecordType.SearchResults,
        sourceFileId,
        lineNumber,
        documentIds = LogPatterns.findDocumentIds(raw)
      )
    )
  }

  private def parseCardQuery(
      raw: String,
      first: String,
      lineNumber: Int,
      sourceFileId: String
  ): Option[ParsedLogRecord] = {
    if (!LogPatterns.CardQuery.matches(first)) return None

    val rest = raw.substring(first.length).trim
    val docMatch = if (rest.nonEmpty) LogPatterns.DocId.findFirstIn(rest) else None
    val onlyDocId = docMatch.exists(id => rest == id)

    if (onlyDocId) {
      Some(
        record(
          RecordType.CardSearchQuery,
          sourceFileId,
          lineNumber,
          documentId = docMatch
        )
      )
    } else {
      Some(
        record(
          RecordType.CardSearchQuery,
          sourceFileId,
          lineNumber,
          queryText = Option(rest).filter(_.nonEmpty)
        )
      )
    }
  }

  private def parseKnownCommand(
      raw: String,
      parts: Array[String],
      first: String,
      lineNumber: Int,
      sourceFileId: String
  ): Option[ParsedLogRecord] = {
    if (!RecordType.KnownCommands.contains(first)) return None

    if (first == RecordType.CardSearchEnd) {
      if (parts.length == 1 || (parts.length == 2 && TimestampParsing.isTimestamp(parts(1)))) {
        return None
      }
      return Some(
        unparseable(raw, sourceFileId, lineNumber, "CARD_SEARCH_END: unexpected tokens")
      )
    }

    if (parts.length < 2) {
      return Some(unparseable(raw, sourceFileId, lineNumber, "missing timestamp"))
    }

    val timestamp = parts(1)

    if (!TimestampParsing.isTimestamp(timestamp)) {
      if (first == "DOC_OPEN" && parts.length >= 3) {
        return Some(
          record(
            RecordType.DocumentOpenNoTs,
            sourceFileId,
            lineNumber,
            documentId = Some(parts(2))
          )
        )
      }
      return Some(
        unparseable(raw, sourceFileId, lineNumber, s"invalid timestamp: $timestamp")
      )
    }

    first match {
      case RecordType.SessionStart =>
        Some(record(RecordType.SessionStart, sourceFileId, lineNumber, timestamp = Some(timestamp)))

      case RecordType.SessionEnd =>
        None

      case RecordType.CardSearchStart =>
        Some(record(RecordType.CardSearchStart, sourceFileId, lineNumber, timestamp = Some(timestamp)))

      case "QS" =>
        Some(
          record(
            RecordType.QuickSearch,
            sourceFileId,
            lineNumber,
            timestamp = Some(timestamp)
          )
        )

      case "DOC_OPEN" =>
        if (parts.length == 4) {
          Some(
            record(
              RecordType.DocumentOpen,
              sourceFileId,
              lineNumber,
              timestamp = Some(timestamp),
              documentId = Some(parts(3))
            )
          )
        } else {
          Some(
            unparseable(
              raw,
              sourceFileId,
              lineNumber,
              s"DOC_OPEN: expected 4 tokens, got ${parts.length}"
            )
          )
        }

      case _ => None
    }
  }
}
