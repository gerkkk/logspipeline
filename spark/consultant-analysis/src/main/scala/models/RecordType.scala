package models

object RecordType {
  final val SessionStart = "SESSION_START"
  final val SessionEnd = "SESSION_END"
  final val QuickSearch = "QUICK_SEARCH"
  final val SearchResults = "SEARCH_RESULTS"
  final val DocumentOpen = "DOCUMENT_OPEN"
  final val DocumentOpenNoTs = "DOCUMENT_OPEN_NO_TS"
  final val CardSearchStart = "CARD_SEARCH_START"
  final val CardSearchQuery = "CARD_SEARCH_QUERY"
  final val CardSearchEnd = "CARD_SEARCH_END"
  final val Unparseable = "UNPARSEABLE"

  val KnownCommands: Set[String] = Set(
    SessionStart,
    SessionEnd,
    "QS",
    "DOC_OPEN",
    CardSearchStart,
    CardSearchEnd
  )
}
