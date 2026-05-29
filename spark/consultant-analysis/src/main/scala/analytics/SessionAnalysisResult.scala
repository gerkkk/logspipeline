package analytics

case class SessionAnalysisResult(
    cardSearchAcc45616Count: Int,
    quickSearchDocsByDate: Set[(String, String)],
    documentOpensByDate: Map[(String, String), Int]
)
