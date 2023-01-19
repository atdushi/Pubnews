package org.example

import org.apache.spark.SparkConf
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.streaming.{Seconds, StreamingContext}
import org.apache.spark.sql.functions.{col, get_json_object}
import org.apache.spark.sql.streaming.Trigger.ProcessingTime

object App {
  def main(args: Array[String]): Unit = {
    parseKafka()
  }

  private def parseKafka(): Unit = {
    val spark = SparkSession.builder()
      .master("local[1]")
      .appName("SparkByExample")
      .getOrCreate()

    spark.sparkContext.setLogLevel("ERROR")

    val ds1 = spark.readStream
      .format("kafka")
      .option("kafka.bootstrap.servers", "localhost:9092")
      .option("subscribe", "foobar")
      .option("startingOffsets", "earliest")
      .option("auto.offset.reset", "earliest")
      .load()

    ds1
      .selectExpr("CAST(key AS STRING)", "CAST(value AS STRING)", "topic", "partition", "offset")
      .writeStream
      .trigger(ProcessingTime("10 seconds")) // save csv every 10 seconds
      .foreachBatch((batchDF: DataFrame, batchId: Long) => {
        batchDF.persist()

        //batchDF.write.format("console").mode("append").save()

        val df = batchDF
          .select(
            get_json_object(col("value"), "$.pub_date").alias("pub_date"),
            get_json_object(col("value"), "$.site").alias("site"),
            get_json_object(col("value"), "$.category").alias("category"),
            get_json_object(col("value"), "$.title").alias("title"))

        df.write
          .format("console")
          .mode("append")
          .save()

        df.write
          .format("csv")
          .mode("append")
          .option("path", "hdfs://localhost:9000/news")
          .option("header", true)
          .option("checkpointLocation", "/tmp/task1/checkpoint")
          .save()

        val u = batchDF.unpersist()
      })
      .start()
      .awaitTermination(10000);
  }

  private def streamTest(ssc: StreamingContext): Unit = {
    val conf = new SparkConf().setMaster("local[2]").setAppName("NetworkWordCount")
    val ssc = new StreamingContext(conf, Seconds(1))
    ssc.sparkContext.setLogLevel("ERROR")

    val lines = ssc.socketTextStream("localhost", 9999)
    val words = lines.flatMap(_.split(" "))
    val pairs = words.map(word => (word, 1))
    val wordCounts = pairs.reduceByKey(_ + _)

    wordCounts.print()

    ssc.start()
    ssc.awaitTermination

    // client:
    // nc -lk 9999
  }

}