package analytics

import lib.TimestampParsing
import models.{ParsedLogRecord, RecordType}

object ConsultantLogAnalytics {

  val TargetDocumentId = "ACC_45616"

  private val RelevantRecordTypes: Set[String] = Set(
    RecordType.SessionStart,
    RecordType.CardSearchStart,
    RecordType.CardSearchQuery,
    RecordType.SearchResults,
    RecordType.QuickSearch,
    RecordType.DocumentOpen,
    RecordType.DocumentOpenNoTs
  )

  def analyzeSession(records: Seq[ParsedLogRecord]): SessionAnalysisResult = {
    val sorted = records.filter(r => RelevantRecordTypes.contains(r.recordType)).sortBy(_.lineNumber)
    val sessionDate = sorted
      .find(_.recordType == RecordType.SessionStart)
      .flatMap(_.timestamp)
      .flatMap(TimestampParsing.extractDate)

    var cardSearchHits = 0
    var inCardSearchBlock = false
    var accInCurrentCardBlock = false

    var lastQuickSearchDate: Option[String] = None
    var pendingQuickSearchDate: Option[String] = None
    val quickSearchDocs = scala.collection.mutable.Set.empty[(String, String)]
    val opens = scala.collection.mutable.Map.empty[(String, String), Int]

    def recordAccInCardBlock(record: ParsedLogRecord): Unit = {
      val inQuery = record.documentId.contains(TargetDocumentId) ||
        record.queryText.exists(_.contains(TargetDocumentId))
      val inResults = record.documentIds.contains(TargetDocumentId)
      if (inQuery || inResults) accInCurrentCardBlock = true
    }

    def closeCardSearchBlock(): Unit = {
      if (inCardSearchBlock && accInCurrentCardBlock) {
        cardSearchHits += 1
      }
      inCardSearchBlock = false
      accInCurrentCardBlock = false
    }

    sorted.foreach { record =>
      record.recordType match {
        case RecordType.CardSearchStart =>
          closeCardSearchBlock()
          inCardSearchBlock = true
          accInCurrentCardBlock = false

        case RecordType.CardSearchQuery =>
          if (inCardSearchBlock) recordAccInCardBlock(record)

        case RecordType.SearchResults =>
          if (inCardSearchBlock) {
            recordAccInCardBlock(record)
            closeCardSearchBlock()
          } else {
            val date = pendingQuickSearchDate.orElse(lastQuickSearchDate)
            date.foreach { d =>
              record.documentIds.foreach { docId =>
                quickSearchDocs += ((d, docId))
              }
            }
            pendingQuickSearchDate = None
          }

        case RecordType.QuickSearch =>
          closeCardSearchBlock()
          val date = record.timestamp.flatMap(TimestampParsing.extractDate)
          lastQuickSearchDate = date
          pendingQuickSearchDate = date

        case RecordType.DocumentOpen =>
          for {
            docId <- record.documentId
            date <- record.timestamp.flatMap(TimestampParsing.extractDate)
          } {
            val key = (date, docId)
            opens.update(key, opens.getOrElse(key, 0) + 1)
          }

        case RecordType.DocumentOpenNoTs =>
          for {
            docId <- record.documentId
            date <- sessionDate
          } {
            val key = (date, docId)
            opens.update(key, opens.getOrElse(key, 0) + 1)
          }

        case _ => ()
      }
    }

    closeCardSearchBlock()

    SessionAnalysisResult(
      cardSearchAcc45616Count = cardSearchHits,
      quickSearchDocsByDate = quickSearchDocs.toSet,
      documentOpensByDate = opens.toMap
    )
  }
}
