package com.consultant

import models.LogEvent
import org.apache.spark.sql.SparkSession
import java.io.File

object ConsultantLogAnalyzer {
  
  def main(args: Array[String]): Unit = {
    val inputPath = if (args.length > 0) args(0) else "./data/"
    val outputPath = if (args.length > 1) args(1) else "./output/"
    
    println(s"Input path: $inputPath")
    println(s"Output path: $outputPath")
    
    val spark = SparkSession.builder()
      .appName("ConsultantPlus Log Analyzer")
      .master("local[*]")
      .getOrCreate()
    
    import spark.implicits._
    
    try {
      println("\nStep 1: Searching for log files...")
      val dataFiles = findDataFiles(new File(inputPath))
      println(s"Found ${dataFiles.length} data files")
      
      if (dataFiles.isEmpty) {
        println("No data files found!")
        return
      }
      
      println("\nStep 2: Reading and parsing log files...")
      val rawRDD = spark.sparkContext.textFile(dataFiles.mkString(","))
      val events = rawRDD.flatMap(LogEvent.parse).cache()
      
      val totalEvents = events.count()
      println(s"Total events parsed: $totalEvents")
      
      // Статистика по командам
      println("\nCommand statistics:")
      val cmdStats = events.map(_.command).countByValue()
      cmdStats.toSeq.sortBy(-_._2).foreach { case (cmd, count) =>
        println(f"  $cmd%-20s: $count%,10d")
      }
      
      // ========== TASK 1 ==========
      println("\n" + "="*80)
      println("TASK 1: Card search count for document ACC_45616")
      println("="*80)
      
      val task1Result = events
        .filter(e => e.command == "NUMERIC_COMMAND" || e.command == "QS")
        .filter { e =>
          val data = e.args.getOrElse("data", "")
          val docs = e.args.getOrElse("document_ids", "")
          (data + docs).contains("ACC_45616")
        }
        .count()
      
      println(s"\nResult: Document ACC_45616 was searched in card $task1Result time(s)")
      
      // Сохраняем Task 1
      spark.sparkContext.parallelize(Seq(("ACC_45616", task1Result)))
        .toDF("document_id", "search_count")
        .write.mode("overwrite").csv(s"$outputPath/task1")
      
      // ========== TASK 2 ==========
      println("\n" + "="*80)
      println("TASK 2: Document opens from quick search by day")
      println("="*80)
      
      // Поисковые документы
      val searchDocs = events
        .filter(e => e.command == "QS" || e.command == "NUMERIC_COMMAND")
        .flatMap { e =>
          val docsStr = e.command match {
            case "QS" => e.args.getOrElse("document_ids", "")
            case "NUMERIC_COMMAND" => e.args.getOrElse("data", "")
            case _ => ""
          }
          
          if (docsStr.nonEmpty) {
            val date = e.timestamp match {
              case Some(ts) => Some(ts.toString.split(" ")(0))
              case None => extractDateFromData(docsStr)
            }
            
            date match {
              case Some(d) =>
                val docIds = LogEvent.extractDocumentIds(docsStr)
                docIds.map(docId => (d, docId))
              case None => Seq.empty
            }
          } else {
            Seq.empty
          }
        }
        .distinct()
        .cache()
      
      val searchDocsCount = searchDocs.count()
      println(s"Search documents count: $searchDocsCount")
      
      // Открытия документов
      val docOpens = events
        .filter(e => e.command == "DOC_OPEN" && e.args.contains("document_id"))
        .flatMap { e =>
          e.timestamp.map { ts =>
            val date = ts.toString.split(" ")(0)
            val docId = e.args("document_id")
            (date, docId)
          }
        }
        .map(t => (t, 1))
        .reduceByKey(_ + _)
        .cache()
      
      val docOpensCount = docOpens.count()
      println(s"Document opens count: $docOpensCount")
      
      // Join
      val searchPairs = searchDocs.map(t => (t, 1))
      val result = searchPairs.join(docOpens)
        .map { case ((date, docId), (_, openCount)) => (date, docId, openCount) }
        .sortBy(t => (t._1, t._2))
      
      val resultCount = result.count()
      println(s"\nTotal unique (date, document) pairs: $resultCount")
      
      if (resultCount > 0) {
        println("\nFirst 50 results:")
        result.take(50).foreach { case (date, docId, count) =>
          println(f"  $date%-12s $docId%-20s $count%,8d")
        }
      } else {
        println("\nNo results found.")
        println(s"  QS count: ${events.filter(_.command == "QS").count()}")
        println(s"  NUMERIC_COMMAND count: ${events.filter(_.command == "NUMERIC_COMMAND").count()}")
        println(s"  DOC_OPEN count: ${events.filter(_.command == "DOC_OPEN").count()}")
      }
      
      // Сохраняем Task 2
      result.toDF("date", "document_id", "open_count")
        .write.mode("overwrite").option("header", "true").csv(s"$outputPath/task2")
      
      println(s"\nResults saved to: $outputPath")
      
    } finally {
      spark.stop()
    }
  }
  
  def extractDateFromData(data: String): Option[String] = {
    val pattern = """(\d{4}-\d{2}-\d{2})""".r
    pattern.findFirstMatchIn(data).map(_.group(1))
  }
  
  def findDataFiles(dir: File): Array[String] = {
    if (dir.exists() && dir.isDirectory) {
      val result = scala.collection.mutable.ArrayBuffer.empty[String]
      for (file <- dir.listFiles()) {
        if (file.isDirectory) {
          result ++= findDataFiles(file)
        } else if (file.getName.matches("\\d+")) {
          result += file.getAbsolutePath
        }
      }
      result.toArray
    } else {
      Array.empty
    }
  }
}
