package com.memsql.streamliner.examples

import org.apache.spark._
import org.apache.spark.rdd._
import org.apache.spark.streaming._
import org.apache.spark.streaming.dstream._
import org.apache.spark.sql._
import org.apache.spark.sql.types._
import com.memsql.spark.connector._
import com.memsql.spark.etl.api._
import com.memsql.spark.etl.utils._

// TODO:
//  1) Figure out how to keep track of file state. we can keep track of names -> md5s in a bucket and periodically check whether any files
//      have changed.
//  2) We need to hook into a lower level API in spark to figure out how to expand a file path to an actual list of files (for the above map)
//  3) We need to persist the map somewhere
//  4) Support non-'\n' line terminators
class FileExtractor extends Extractor {
  var first = true
  var repeat = false
  var filePath = ""
  def schema: StructType = StructType(StructField("data", StringType, false) :: Nil)

  override def initialize(ssc: StreamingContext, sqlContext: SQLContext, config: PhaseConfig, batchInterval: Long,
                          logger: PhaseLogger): Unit = {
    val userConfig = config.asInstanceOf[UserExtractConfig]
    filePath = userConfig.getConfigString("path") match {
      case Some(s) => s
      case None => throw new IllegalArgumentException("Missing required argument 'path'")
    }

    repeat = userConfig.getConfigBoolean("repeat").getOrElse(false)

    if (filePath.startsWith("s3n://")) {
      val access_key = userConfig.getConfigString("aws_access_key") match {
        case Some(s) => s
        case None => throw new IllegalArgumentException("Missing required argument 'aws_access_key' (because path starts with s3n)")
      }
      val secret_key = userConfig.getConfigString("aws_secret_key") match {
        case Some(s) => s
        case None => throw new IllegalArgumentException("Missing required argument 'aws_secret_key' (because path starts with s3n)")
      }

      // Ideally would like to put this in the URL (i.e. "s3n://ACCESS_KEY:SECRET_KEY@path"), but am blocked
      // https://issues.apache.org/jira/browse/HADOOP-3733
      val sparkContext = sqlContext.sparkContext
      val current_access_key = sparkContext.hadoopConfiguration.get("fs.s3.awsAccessKeyId")
      val current_secret_key = sparkContext.hadoopConfiguration.get("fs.s3n.awsSecretAccessKey")
      if (current_access_key != null && current_access_key != access_key) {
        throw new IllegalArgumentException("Access key is already set and is different")
      }
      if (current_secret_key != null && current_secret_key != secret_key) {
        throw new IllegalArgumentException("Secret key is already set and is different")
      }

      sparkContext.hadoopConfiguration.set("fs.s3n.awsAccessKeyId", access_key)
      sparkContext.hadoopConfiguration.set("fs.s3n.awsSecretAccessKey", secret_key)
    }

  }

  override def cleanup(ssc: StreamingContext, sqlContext: SQLContext, config: PhaseConfig, batchInterval: Long,
                       logger: PhaseLogger): Unit = {
    // TODO: should "flush" state here
  }

  override def next(ssc: StreamingContext, time: Long, sqlContext: SQLContext, config: PhaseConfig, batchInterval: Long,
                    logger: PhaseLogger): Option[DataFrame] = {
    if (!first) {
      logger.info("There is nothing left to serve because first=false")
      return None
    }
    if (!repeat) {
      first = false
    }
    logger.info("Grabbing files now for path: [" + filePath + "]")
    val rowRDD = sqlContext.sparkContext.textFile(filePath).map(x => Row(x))
    val df = sqlContext.createDataFrame(rowRDD, schema)
    Some(df)
  }
}
