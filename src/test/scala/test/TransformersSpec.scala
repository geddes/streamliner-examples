package test

import com.memsql.spark.etl.api.UserTransformConfig
import com.memsql.spark.etl.utils.ByteUtils
import com.memsql.streamliner.examples._
import com.memsql.spark.connector.dataframe.JsonType
import org.apache.spark.sql.{Row, SQLContext}
import org.apache.spark.sql.types._
import spray.json.{JsBoolean, JsObject, JsString}
import test.util.{UnitSpec, TestLogger, LocalSparkContext}

class TransformersSpec extends UnitSpec with LocalSparkContext {
  val emptyConfig = UserTransformConfig(class_name = "Test", value = JsString("empty"))
  val logger = new TestLogger("test")

  var sqlContext: SQLContext = _

  override def beforeEach(): Unit = {
    super.beforeEach()
    sqlContext = new SQLContext(sc)
  }

  "EvenNumbersOnlyTransformer" should "only emit even numbers" in {
    val transform = new EvenNumbersOnlyTransformer

    val schema = StructType(StructField("number", IntegerType, false) :: Nil)
    val sampleData = List(1,2,3)
    val rowRDD = sqlContext.sparkContext.parallelize(sampleData).map(Row(_))
    val dfIn = sqlContext.createDataFrame(rowRDD, schema)

    val df = transform.transform(sqlContext, dfIn, emptyConfig, logger)
    assert(df.schema == schema)
    assert(df.first == Row(2))
    assert(df.count == 1)
  }

  "EvenNumbersOnlyTransformer" should "only accept IntegerType fields" in {
    val transform = new EvenNumbersOnlyTransformer

    val schema = StructType(StructField("column", StringType, false) :: Nil)
    val sampleData = List(1,2,3)
    val rowRDD = sqlContext.sparkContext.parallelize(sampleData).map(Row(_))
    val dfIn = sqlContext.createDataFrame(rowRDD, schema)

    val e = intercept[IllegalArgumentException] {
      transform.transform(sqlContext, dfIn, emptyConfig, logger)
    }
    assert(e.getMessage() == "The first column of the input DataFrame should be IntegerType")
  }

  "ConfigurableNumberParityTransformer" should "support skipping odd numbers" in {
    val transform = new ConfigurableNumberParityTransformer
    val schema = StructType(StructField("number", IntegerType, false) :: Nil)
    val sampleData = List(1,2,3)
    val rowRDD = sqlContext.sparkContext.parallelize(sampleData).map(Row(_))
    val dfIn = sqlContext.createDataFrame(rowRDD, schema)

    val config = UserTransformConfig(
      class_name="test",
      value=JsObject("filter" -> JsObject("odd" -> JsBoolean(false)))
    )

    val df = transform.transform(sqlContext, dfIn, config, logger)
    assert(df.first == Row(2))
    assert(df.count == 1)
  }

  it should "support skipping even numbers" in {
    val transform = new ConfigurableNumberParityTransformer
    val schema = StructType(StructField("number", IntegerType, false) :: Nil)
    val sampleData = List(1,2,3)
    val rowRDD = sqlContext.sparkContext.parallelize(sampleData).map(Row(_))
    val dfIn = sqlContext.createDataFrame(rowRDD, schema)

    val config = UserTransformConfig(
      class_name="test",
      value=JsObject("filter" -> JsObject( "even" -> JsBoolean(false) ))
    )

    val df = transform.transform(sqlContext, dfIn, config, logger)
    assert(df.first == Row(1))
    assert(df.count == 2)
  }

  it should "handle an empty filter" in {
    val transform = new ConfigurableNumberParityTransformer
    val schema = StructType(StructField("number", IntegerType, false) :: Nil)
    val sampleData = List(1,2,3)
    val rowRDD = sqlContext.sparkContext.parallelize(sampleData).map(Row(_))
    val dfIn = sqlContext.createDataFrame(rowRDD, schema)

    val config = UserTransformConfig(
      class_name="test",
      value=JsObject("filter" -> JsObject())
    )

    val df = transform.transform(sqlContext, dfIn, config, logger)
    assert(df.first == Row(1))
    assert(df.count == 3)
  }

  "JSONMultiColsTransformer" should "insert rows with 2 fields id, txt" in {
    val transform = new JSONMultiColsTransformer
    val schema = StructType(StructField("data", StringType, false) :: Nil)
    val sampleData = List(
      """{"id": "a001", "txt": "hello"}""",
      """{"id": "b002", "txt": "world", "foo": "bar"}""",  // foo field ignored
      """{"xid": "c001", "txt": "text"}"""  // xid ignored, id NULL
    )//.map(line => ByteUtils.utf8StringToBytes(line))
    val rowRDD = sqlContext.sparkContext.parallelize(sampleData).map(Row(_))
    val dfIn = sqlContext.createDataFrame(rowRDD, schema)

    val df = transform.transform(sqlContext, dfIn, emptyConfig, logger)    
    assert(df.schema == StructType(Array(
      StructField("id", StringType, true),
      StructField("txt", StringType, true)
    )))
    assert(df.count == 3)
    assert(df.first == Row("a001", "hello"))
    for ( (a, b) <- df.head(3).zip(Array(
      Row("a001", "hello"),
      Row("b002", "world"),
      Row(null, "text")
    ))) {
      assert(a == b)
    }
  }

  "JSONCheckIdTransformer" should "insert rows with 1 field of type JSON" in {
    val transform = new JSONCheckIdTransformer
    val schema = StructType(StructField("testdata", StringType, false) :: Nil)
    val sampleData = List(
      """{"id": "a001", "txt": "hello"}"""
    )
    val rowRDD = sqlContext.sparkContext.parallelize(sampleData).map(Row(_))
    val dfIn = sqlContext.createDataFrame(rowRDD, schema)

    val columnName = "test"
    val config = UserTransformConfig(
      class_name = "test",
      value = JsObject("column_name" -> JsString(columnName))
    )

    val df = transform.transform(sqlContext, dfIn, config, logger)
    assert(df.schema == StructType(Array(StructField(columnName, JsonType, true))))
    assert(df.count == 1)
    assert(df.first.toString == """[{"id": "a001", "txt": "hello"}]""")
  }

  "JSONCheckIdTransformer" should "insert rows with 1 field of type JSON on input Array[Byte]" in {
    val transform = new JSONCheckIdTransformer
    val schema = StructType(StructField("testdata", BinaryType, false) :: Nil)
    val sampleData = List(
      """{"id": "a001", "txt": "hello"}"""
    ).map(ByteUtils.utf8StringToBytes)
    val rowRDD = sqlContext.sparkContext.parallelize(sampleData).map(Row(_))
    val dfIn = sqlContext.createDataFrame(rowRDD, schema)

    val columnName = "test"
    val config = UserTransformConfig(
      class_name = "test",
      value = JsObject("column_name" -> JsString(columnName))
    )

    val df = transform.transform(sqlContext, dfIn, config, logger)
    assert(df.schema == StructType(Array(StructField(columnName, JsonType, true))))
    assert(df.count == 1)
    assert(df.first.toString == """[{"id": "a001", "txt": "hello"}]""")
  }

  it should "skip rows with no id field" in {
    val transform = new JSONCheckIdTransformer
    val schema = StructType(StructField("testdata", StringType, false) :: Nil)
    val sampleData = List(
      """{"id": "a001", "txt": "hello"}""",
      """{"id": "b002", "txt": "world", "foo": "bar"}""",  // foo field
      """{"xid": "c001", "txt": "text"}"""  // id not available, row skipped
    )
    val rowRDD = sqlContext.sparkContext.parallelize(sampleData).map(Row(_))
    val dfIn = sqlContext.createDataFrame(rowRDD, schema)

    val df = transform.transform(sqlContext, dfIn, emptyConfig, logger)
    assert(df.schema == StructType(Array(StructField("data", JsonType, true))))
    assert(df.count == 2)
  }

  "TwitterHashtagTransformer" should "should extract all the hashtags from the tweets resource" in {
    val transform = new TwitterHashtagTransformer
    val schema = StructType(StructField("testdata", StringType, false) :: Nil)
    val tweetsURI = getClass.getResource("/tweets").toURI
    val tweets = sc.textFile(tweetsURI.toURL.toString)
    val rowRDD = tweets.map(Row(_))
    val dfIn = sqlContext.createDataFrame(rowRDD, schema)

    val columnName = "test"
    val config = UserTransformConfig(
      class_name = "TwitterHashtagTransformer",
      value = JsObject("column_name" -> JsString(columnName))
    )

    val df = transform.transform(sqlContext, dfIn, config, logger)

    assert(df.schema == StructType(Array(StructField(columnName, StringType, true))))
    // looked in the tweets file (see resources/tweets) and found the
    // first hashtag in the first tweet
    assert(df.first == Row("MTVFANWARSArianators"))
    assert(df.count == 141)
  }

  "S3AccessLogsTransformer" should "correctly parse S3 logs" in {
    // example from AWS website
    val logOutput = List(
      """79a59df900b949e55d96a1e698fbacedfd6e09d98eacf8f8d5218e7cd47ef2be mybucket [06/Feb/2014:00:00:38 +0000] 192.0.2.3
        | 79a59df900b949e55d96a1e698fbacedfd6e09d98eacf8f8d5218e7cd47ef2be 3E57427F3EXAMPLE REST.GET.VERSIONING - "GET/mybucket?versioning HTTP/1.1" 200
        | - 113 - 7 - "-" "S3Console/0.4" -""".stripMargin.replaceAll("\n", "")
    )

    val transform = new S3AccessLogsTransformer
    val schema = StructType(StructField("testdata", StringType, false) :: Nil)
    val rowRDD = sqlContext.sparkContext.parallelize(logOutput).map(Row(_))
    val dfIn = sqlContext.createDataFrame(rowRDD, schema)

    val df = transform.transform(sqlContext, dfIn, emptyConfig, logger)
    assert(df.count == 1)

    val first = df.first

    assert(first.getAs[String]("bucket") == "mybucket")
    assert(first.getAs[String]("ip") == "192.0.2.3")
    assert(first.getAs[String]("user_agent") == "S3Console/0.4")
    assert(first.getAs[String]("version_id") == "-")
    assert(first.getAs[Int]("http_status") == 200)
    assert(first.getAs[Int]("object_size") === null)
  }
}
