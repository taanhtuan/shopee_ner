package shopee.recognition.entity

import conf.{ArgsConfig, ConfigKeys}
import opt.Operation
import org.apache.spark.ml.feature.{IndexToString, NGram, RegexTokenizer}
import org.apache.spark.ml.{Pipeline, PipelineModel}
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types.MetadataBuilder
import org.apache.spark.sql.{DataFrame, SaveMode, SparkSession}

import scala.collection.mutable

/**
  * Implementation of predicting process once we got models and their attributes after training process
  *
  * @author TuanTA
  * @since 2017-04-05 17:17
  */
class PredictProcess(conf:ArgsConfig) extends Operation {

  override protected type InputType = DataFrame
  override protected type TransformType = DataFrame
  override protected type ProcessType = DataFrame

  override def appName:String = conf.getAppName

  override def master:String = conf.getMaster


  /**
    * Data to be predicted, will support streaming later
    *
    * @param spark session created when starting process
    * @return [[InputType]], it must be specified in implemented class
    */
  override def read()(implicit spark:SparkSession):DataFrame =
    spark.sqlContext
      .read
      .option("header", "true")
      .option("delimiter", ",")
      .option("inferSchema", "true")
      .csv(conf.option[String](ConfigKeys.INPUT))


  /**
    * Parse raw data to feature for predicting process
    *
    * @param df original data
    * @param spark session created when starting process
    * @return [[TransformType]], it must be specified in implemented class
    */
  override def transform(df:DataFrame)(implicit spark:SparkSession):DataFrame = {

    val corpusTokenizer = new RegexTokenizer()
      .setMinTokenLength(3)
      .setPattern("\\w+").setGaps(false)
      .setToLowercase(true)
      .setInputCol("product_name")
      .setOutputCol("tokens")

    val pipeline = new Pipeline().setStages(Array(corpusTokenizer)).fit(df)
    pipeline.transform(df).select("product_name", "tokens")
  }


  /**
    * Predict based on extracted features
    *
    * @param df features for predicting process
    * @param spark session created when starting process
    * @return [[ProcessType]], it must be specified in implemented class
    */
  override def process(df:DataFrame)(implicit spark:SparkSession):DataFrame = {
    // load model and do predicting
    val model = PipelineModel.load(conf.option[String](ConfigKeys.MODEL_TERM))
    val result = model.transform(prefineTokens(df))

    postProcess(result)
  }


  /** Extract 2-grams and 3-grams from single tokens, then merges them into original tokens */
  private def prefineTokens(df:DataFrame)(implicit spark:SparkSession):DataFrame = {
    import spark.implicits._

    val ngram2 = new NGram().setN(2)
      .setInputCol("tokens")
      .setOutputCol("ngrams_2")

    val ngram3 = new NGram().setN(3)
      .setInputCol("tokens")
      .setOutputCol("ngrams_3")

    val mergeTokens = udf {(g1:Seq[String], g2:Seq[String], g3:Seq[String]) => g1 ++ g2 ++ g3 }

    new Pipeline().setStages(Array(ngram2, ngram3))
      .fit(df).transform(df)
      .withColumn("tokens", mergeTokens($"tokens", $"ngrams_2", $"ngrams_3"))
  }


  /**
    * Performs 3 post processing steps:
    * 1. Map predicted label in numeric back to text.
    * 2. Do POS tagger to get more candidate named entities.
    * 3. Then, extract brands based on found named entities
    *
    * @param df result of prediciting
    * @param spark session
    * @return final result
    */
  private def postProcess(df:DataFrame)(implicit spark:SparkSession):DataFrame = {
    import spark.implicits._

    // load brands, Map[term, Array(brands)]
    val brandAttrs = spark.read.parquet(conf.option[String](ConfigKeys.ATTR_BRAND))
    val tb = brandAttrs.rdd.collect
      .map(r => (r.getAs[String]("term"), r.getAs[String]("brand")))
      .groupBy(_._1)
      .map { case (k, v) => (k, v.map(_._2)) }

    // load vocabularies
    val termAttrs = spark.read.parquet(conf.option[String](ConfigKeys.ATTR_TERM))
    val x:Map[String, Seq[String]] = termAttrs.rdd.collect.map { r =>
      (r.getAs[String](ConfigKeys.ATTR_TERM), r.getAs[Seq[String]]("value"))
    }.toMap

    // load label indices metadata (from vocabularies) to map predicted classes from numeric to text
    val terms = x.getOrElse("labels", Seq.empty[String])
    val labelAttribute = new MetadataBuilder()
      .putString("name", "x")
      .putString("type", "nominal")
      .putStringArray("vals", terms.toArray)
      .build()
    val labelMeta = new MetadataBuilder()
      .putMetadata("ml_attr", labelAttribute)
      .build()


    val converter = new IndexToString()
      .setInputCol("prediction")
      .setOutputCol("predictedLabel")

    // process with POS tagger again
    val pos = udf { (title:String) =>
      POS.extract(Utils.textCleaning(title))
        .filter(_.split(" +").length < 4)
        .map(_.toLowerCase)
    }

    // remove mismatch predicted label which does not exist in tokens
    val eliminateMismatch = udf { (prediction:String, tokens:Seq[String]) =>
      if (tokens.contains(prediction)) prediction
      else tokens.intersect(terms).mkString(", ")
    }

    // select right terms
    val term = udf { (t1:String, t2:Seq[String]) =>
      val ts =
        if (t1.nonEmpty) {
          val x1 = t1.split(",").map(_.trim).toSeq
          x1 ++ t2.filterNot(t => x1.exists(x => t.contains(x)))
        } else t2
      //println(s"$t1 + $t2 -> $ts")
      ts.filterNot(x => ts.exists(y => x != y && x.contains(y))).mkString(", ")
    }

    // get brands corresponding to term if they exist in tokens
    val predictBrand = udf { (terms:String, tokens:Seq[String]) =>
      terms.split(",").map(_.trim).flatMap { term =>
        tb.getOrElse(term, None) match {
          case brands:Array[String] => brands.filter(tokens.contains)
          case None => Nil // brand not found in training dataset
        }
      }.mkString(", ")
    }

    // execute all transformations to get final result
    converter
      .transform(df.withColumn("prediction", $"prediction".as("prediction", labelMeta)))
      .withColumn("terms_1", eliminateMismatch($"predictedLabel", $"tokens"))
      .withColumn("terms_2", pos($"product_name"))
      .withColumn("core_terms", term($"terms_1", $"terms_2"))
      .withColumn("brands", predictBrand($"core_terms", $"tokens"))
  }


  /**
    * Persist result to CSV file
    *
    * @param df result
    * @param spark session created when starting process
    */
  override def write(df:DataFrame)(implicit spark:SparkSession):Unit = {
    //val stringify = udf((vs:Seq[String]) => s"""${vs.map(s => s"[$s]").mkString(", ")}""")

    df//.withColumn("core_terms", stringify($"core_terms"))
      .select("product_name", "core_terms", "brands")
      .write
      .format("csv")
      .option("header", "true")
      .mode(SaveMode.Overwrite)
      .save(s"${conf.option[String](ConfigKeys.OUTPUT)}")
  }
}
