import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions._
import java.sql.Timestamp
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

val spark = SparkSession.builder()
  .appName("ConsultantPlus Analyzer")
  .master("local[*]")
  .getOrCreate()

import spark.implicits._

def parseLine(line: String): Option[(String, Timestamp, Map[String, String])] = {
  val trimmed = line.trim
  if (trimmed.isEmpty) return None
  
  val parts = trimmed.split("\\s+", 3)
  if (parts.length < 2) return None
  
  val cmd = parts(0)
  val second = parts(1)
  
  val isNumber = cmd.replaceFirst("^-", "").forall(_.isDigit)
  val isTimestamp = second.matches("\\d{2}\\.\\d{2}\\.\\d{4}_\\d{2}:\\d{2}:\\d{2}")
  
  if (isNumber && !isTimestamp) {
    return Some(("NUMERIC", null, Map("data" -> trimmed)))
  }
  
  val formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy_HH:mm:ss")
  val timestamp = try {
    val dt = LocalDateTime.parse(second, formatter)
    Timestamp.valueOf(dt)
  } catch { case _: Exception => return None }
  
  val args = if (parts.length == 3) {
    val rest = parts(2)
    cmd match {
      case "QS" =>
        val brace = """\{([^}]*)\}\s*(.*)""".r
        rest match {
          case brace(q, d) => Map("query" -> q, "docs" -> d)
          case _ => Map("docs" -> rest)
        }
      case "DOC_OPEN" =>
        val p = rest.split("\\s+", 2)
        if (p.length == 2) Map("search_id" -> p(0), "doc_id" -> p(1))
        else Map("search_id" -> rest)
      case _ => Map("data" -> rest)
    }
  } else Map.empty[String, String]
  
  Some((cmd, timestamp, args))
}

// Поиск файлов
val dataDir = "/Users/gerk/Desktop/LogsPipeline/data/"
val files = (0 until 10000)
  .map(_.toString)
  .filter(f => new java.io.File(dataDir + f).exists())
  .map(f => dataDir + f)

println(s"Found ${files.length} files")

if (files.isEmpty) {
  println("No files found!")
  System.exit(0)
}

// Чтение и парсинг
val df = spark.sparkContext.textFile(files.mkString(","))
  .flatMap(parseLine)
  .toDF("cmd", "ts", "args")
  .cache()

val totalEvents = df.count()
println(s"Total events: $totalEvents")

// Задача 1
val task1 = df.filter($"cmd" === "CARD_SEARCH_START")
  .filter($"args"("data").contains("ACC_45616"))
  .count()

println("\n" + "="*60)
println(s"ЗАДАЧА 1: Документ ACC_45616 искали в карточке $task1 раз")
println("="*60)

// Задача 2
val qs = df.filter($"cmd" === "QS")
  .filter($"args"("docs").isNotNull)
  .select(to_date($"ts").as("date"), split($"args"("docs"), " ").as("docs"))
  .withColumn("doc", explode($"docs"))
  .filter($"doc".rlike("[A-Z]+_\\d+"))
  .select($"date", $"doc".as("doc_id"))
  .distinct()

val opens = df.filter($"cmd" === "DOC_OPEN")
  .filter($"args"("doc_id").isNotNull)
  .select(to_date($"ts").as("date"), $"args"("doc_id").as("doc_id"))
  .groupBy($"date", $"doc_id")
  .agg(count("*").as("opens"))

val result = qs.join(opens, Seq("date", "doc_id"), "inner")
  .orderBy($"date", $"doc_id")

println("\n" + "="*60)
println("ЗАДАЧА 2: Открытия документов из быстрого поиска по дням")
println("="*60)
println(s"Всего записей: ${result.count()}")
result.show(20, truncate = false)

// Сохранение результата
result.write.mode("overwrite").option("header", "true").csv("task2_result_spark")
println("\nРезультаты сохранены в task2_result_spark/")

spark.stop()
System.exit(0)
