package com.consultant

import analytics.{ConsultantLogAnalytics, SessionAnalysisResult, SessionFileParser}
import lib.FileDiscovery
import models.{ParsedLogRecord, RecordType}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{SaveMode, SparkSession}

object ConsultantLogParseJob {

  def main(args: Array[String]): Unit = {
    val inputPath = args.lift(0).getOrElse("../../data/data")
    val outputPath = args.lift(1).getOrElse("../../data/output/parsed")
    val partitionsArg = args.lift(2).map(_.toInt).filter(_ > 0)

    val spark = SparkSession.builder()
      .appName("ConsultantPlus Log Parser")
      .getOrCreate()

    try {
      run(spark, inputPath, outputPath, partitionsArg)
    } finally {
      spark.stop()
    }
  }

  private def run(
      spark: SparkSession,
      inputPath: String,
      outputPath: String,
      partitionsArg: Option[Int]
  ): Unit = {
    val sc = spark.sparkContext
    val filePaths = FileDiscovery.listNumericFiles(inputPath)

    if (filePaths.isEmpty) {
      println(s"[ConsultantLogParseJob] no session files under $inputPath")
      return
    }

    val numPartitions = partitionsArg.getOrElse(
      math.min(filePaths.size, math.max(sc.defaultParallelism, 1) * 2).max(1)
    )

    println(s"[ConsultantLogParseJob] start input=$inputPath output=$outputPath files=${filePaths.size}")

    val recordsRdd = parseFiles(sc, filePaths, numPartitions).cache()

    val unparseableRdd = recordsRdd.filter(_.recordType == RecordType.Unparseable)
    val sessionResultsRdd = analyzeSessions(
      recordsRdd.filter(_.recordType != RecordType.Unparseable)
    ).cache()

    val task1Total = aggregateTask1(sessionResultsRdd)
    val task2Rdd = aggregateTask2(sessionResultsRdd).cache()

    writeOutput(spark, outputPath, task1Total, task2Rdd, unparseableRdd)

    recordsRdd.unpersist()
    sessionResultsRdd.unpersist()
    task2Rdd.unpersist()

    println(s"[ConsultantLogParseJob] done output=$outputPath")
  }

  private def parseFiles(
      sc: org.apache.spark.SparkContext,
      filePaths: Seq[String],
      numPartitions: Int
  ): RDD[ParsedLogRecord] =
    sc.parallelize(filePaths, numPartitions)
      .flatMap(SessionFileParser.parseFile)

  private def analyzeSessions(recordsRdd: RDD[ParsedLogRecord]): RDD[SessionAnalysisResult] =
    recordsRdd
      .groupBy(_.sourceFileId)
      .map { case (_, records) =>
        ConsultantLogAnalytics.analyzeSession(records.toSeq)
      }

  private def aggregateTask1(sessionResultsRdd: RDD[SessionAnalysisResult]): Long =
    sessionResultsRdd
      .map(_.cardSearchAcc45616Count.toLong)
      .reduce(_ + _)

  private def aggregateTask2(
      sessionResultsRdd: RDD[SessionAnalysisResult]
  ): RDD[(String, String, Long)] = {
    val quickSearchDocKeys = sessionResultsRdd
      .flatMap(_.quickSearchDocsByDate)
      .map(pair => (pair, 1L))
      .reduceByKey(_ + _)
      .keys

    val opensByDateDoc = sessionResultsRdd
      .flatMap(_.documentOpensByDate.toSeq)
      .map { case (key, count) => (key, count.toLong) }
      .reduceByKey(_ + _)

    quickSearchDocKeys
      .map { case (date, docId) => ((date, docId), ()) }
      .join(opensByDateDoc)
      .map { case ((date, docId), (_, openCount)) =>
        (date, docId, openCount)
      }
      .sortBy { case (date, docId, _) => (date, docId) }
  }

  private def writeOutput(
      spark: SparkSession,
      outputPath: String,
      task1Total: Long,
      task2Rdd: RDD[(String, String, Long)],
      unparseableRdd: RDD[ParsedLogRecord]
  ): Unit = {
    import spark.implicits._

    unparseableRdd
      .map { r =>
        (
          r.sourceFileId,
          r.lineNumber,
          r.parseError.getOrElse(""),
          r.rawLine.getOrElse("")
        )
      }
      .coalesce(1)
      .toDF("source_file_id", "line_number", "parse_error", "raw_line")
      .write
      .mode(SaveMode.Overwrite)
      .option("header", "true")
      .csv(s"$outputPath/unparseable_lines")

    spark.sparkContext
      .parallelize(Seq((ConsultantLogAnalytics.TargetDocumentId, task1Total)), 1)
      .toDF("document_id", "card_search_count")
      .write
      .mode(SaveMode.Overwrite)
      .option("header", "true")
      .csv(s"$outputPath/task1_card_search_acc45616")

    val task2Df = task2Rdd
      .coalesce(1)
      .toDF("date", "document_id", "open_count")

    task2Df.write
      .mode(SaveMode.Overwrite)
      .option("header", "true")
      .parquet(s"$outputPath/task2_quick_search_opens")

    task2Df.write
      .mode(SaveMode.Overwrite)
      .option("header", "true")
      .csv(s"$outputPath/task2_quick_search_opens_csv")
  }
}
