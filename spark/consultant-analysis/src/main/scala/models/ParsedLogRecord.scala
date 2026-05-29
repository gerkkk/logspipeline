package models

// Одна распознанная строка лога
case class ParsedLogRecord(
    recordType: String,
    sourceFileId: String,
    lineNumber: Int,
    timestamp: Option[String] = None,
    documentId: Option[String] = None,
    documentIds: Seq[String] = Seq.empty,
    queryText: Option[String] = None,
    parseError: Option[String] = None,
    rawLine: Option[String] = None
)
