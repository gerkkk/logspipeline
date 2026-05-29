# Spark Consultant Log Parser

## Сборка

```bash
mvn clean package
```

## Запуск (local)

```bash
export SPARK_LOCAL_IP=127.0.0.1

spark-submit \
  --class com.consultant.ConsultantLogParseJob \
  --master 'local[*]' \
  --conf spark.driver.host=127.0.0.1 \
  --conf spark.driver.bindAddress=127.0.0.1 \
  target/spark-consultant-analysis-1.0.0.jar \
  ../../data/data \
  ../../data/output/parsed \
  8
```

Аргументы: `inputDir` `outputDir` `[numPartitions]`.
