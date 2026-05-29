import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions._

val spark = SparkSession.builder()
  .appName("Simple Analyzer")
  .master("local[*]")
  .getOrCreate()

val dataPath = "/Users/gerk/Desktop/LogsPipeline/data/data/"
println(s"Reading files from: $dataPath")

// Используем wholeTextFiles для чтения всех файлов
val allFiles = spark.sparkContext.wholeTextFiles(dataPath)
val lines = allFiles.flatMap { case (path, content) => 
  content.split("\n").filter(_.nonEmpty)
}

val df = lines.toDF("line").cache()
val totalLines = df.count()
println(s"Total lines: $totalLines")

// Задача 1: ищем ACC_45616
val accLines = df.filter($"line".contains("ACC_45616"))
val accCount = accLines.count()
println(s"\n=== TASK 1 ===")
println(s"ACC_45616 found in $accCount lines")

if (accCount > 0) {
  println("Sample lines:")
  accLines.show(10, truncate = false)
}

// Задача 2: DOC_OPEN статистика
val docOpens = df.filter($"line".startsWith("DOC_OPEN"))
println(s"\nDOC_OPEN count: ${docOpens.count()}")
println("Sample DOC_OPEN:")
docOpens.show(5, truncate = false)

// QS статистика
val qsLines = df.filter($"line".startsWith("QS"))
println(s"\nQS count: ${qsLines.count()}")
println("Sample QS:")
qsLines.show(3, truncate = false)

spark.stop()
