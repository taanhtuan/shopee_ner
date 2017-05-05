package shopee.recognition.entity

import conf.{ArgsConfig, ConfigKeys}
import opt.Operation
import org.apache.spark.ml.classification.{LogisticRegression, OneVsRest, RandomForestClassifier}
import org.apache.spark.ml.evaluation.MulticlassClassificationEvaluator
import org.apache.spark.ml.feature.{CountVectorizer, HashingTF, NGram, PCA, RegexTokenizer, StopWordsRemover, StringIndexer, VectorAssembler}
import org.apache.spark.ml.tuning.{CrossValidator, ParamGridBuilder}
import org.apache.spark.ml.{Pipeline, PipelineModel}
import org.apache.spark.sql.functions._
import org.apache.spark.sql.{DataFrame, SaveMode, SparkSession}

import scala.collection.mutable

/**
  * Trains models to extract named entities and brands from givens product names
  *
  * @author TuanTA
  * @since 2017-04-05 17:12
  */
class TrainProcess(conf:ArgsConfig) extends Operation {

  override protected type InputType = DataFrame
  override protected type TransformType = DataFrame
  override protected type ProcessType = (PipelineModel, DataFrame, DataFrame)

  override def appName:String = conf.getAppName

  override def master:String = conf.getMaster

  /**
    * Read all training datasets in CSV files
    *
    * @param spark session created when starting process
    * @return [[InputType]], it must be specified in implemented class
    */
  override def read()(implicit spark:SparkSession):DataFrame =
    spark.sqlContext
      .read
//      .format("org.apache.spark.sql.execution.datasources.csv.CSVFileFormat")
      .option("header", "true")
      .option("delimiter", ",")
      .option("inferSchema", "true")
      .csv(conf.option[String](ConfigKeys.INPUT))


  /**
    * Clean and extract feature for learning model
    *
    * @param df raw dataset collected from multiple CSV files, stored in data frame
    * @param spark session created when starting process
    * @return [[TransformType]], it must be specified in implemented class
    */
  override def transform(df:DataFrame)(implicit spark:SparkSession):DataFrame = {
    import spark.implicits._

    // Step 1. Extract orignal data
    val stopWordNorm = udf { (s:String) => s.split(",").map(_.trim.toLowerCase) }

    val targetTokenizer = udf { (terms:String) => terms.split(",").map(_.trim.toLowerCase).filterNot(_ == "") }

    // only select candidate fields before extracting features
    val originDf = df.na.fill("", Seq("brand", "stop_words"))
      .withColumn("targets", targetTokenizer($"core_terms"))
      .withColumn("brands", targetTokenizer($"brand"))
      .withColumn("stop_words", stopWordNorm($"stop_words"))
      .select(
        $"product_name" as "title",
        $"stop_words",
        $"targets",
        $"brands"
      )

    // Step 2.
    val titleTokenizer = new RegexTokenizer()
      .setMinTokenLength(2)
      .setPattern("\\w+").setGaps(false)
      .setToLowercase(true)
      .setInputCol("title")
      .setOutputCol("tokens")

    val remover = new StopWordsRemover()
      .setCaseSensitive(false)
      .setInputCol(titleTokenizer.getOutputCol)
      .setOutputCol("filtered")


    val stopwordRemover = udf { (tokens:Seq[String], stopwords:Seq[String]) => tokens.filterNot(stopwords.toSet) }

    val pos = udf { (title:String) => POS.extract(title) }

    // set up pipeline for transforming features
    val pipeline = new Pipeline().setStages(Array(
//      labelTokenizer, brandTokenizer, titleTokenizer
      titleTokenizer
    )).fit(originDf)

    // Step 3. Extract features
    // transform features for learning process
    pipeline.transform(originDf)
//      .withColumn("pos", pos($"title"))
      .withColumn("tokens", stopwordRemover($"tokens", $"stop_words")) // remove stop words in tokens
      // split labels to single label, keep same feature set
      .withColumn("target", explode(
        when($"targets".isNotNull, $"targets")
          .otherwise(array(lit(null).cast("string"))) // if null explode an array<string> with a single null
      ))
      //.withColumn("target", explode_outer($"targets")) // use instead of above when updating spark to version 2.2
      .select("tokens", "target", "brands", "stop_words")
  }


  /**
    * Learn models: named entity extracting model and brand extracting model
    *
    * @param df data frame contains feature for learning
    * @param spark session created when starting process
    * @return [[ProcessType]], it must be specified in implemented class
    */
  def process(df:DataFrame)(implicit spark:SparkSession):(PipelineModel, DataFrame, DataFrame) = {

//    val Array(trainingData, testData) = df.randomSplit(Array(0.7, 0.3), seed = 11L)

    val indexer = new StringIndexer()
      .setInputCol("target")
      .setOutputCol("label")

    // prepare train data
    val train = indexer.fit(df).transform(df)

    // learning processes
    val termModel = trainTermModel(train)


    // Alternatively, extract metadata for using in predict process
    // 1. Extract brands
    val brandAttribute = extractBrands(train)

    // 2. Extract terms
    val labelSchema = train.schema(indexer.getOutputCol)
    val labels = labelSchema.metadata.getMetadata("ml_attr").getStringArray("vals")
    // store metadata in a dataframe before passing to writer
    val termAttribute = spark.createDataFrame(Seq(
      ("labels", labels)
    )).toDF(ConfigKeys.ATTR_TERM, "value")

    (termModel, brandAttribute, termAttribute)
  }


  /** Pipeline to extract named entities */
  private def trainTermModel(df:DataFrame)(implicit spark:SparkSession):PipelineModel = {
    import spark.implicits._

    val dft = prefineTokens(df)

    val stopwords = df.rdd.collect().flatMap(r => r.getAs[Seq[String]]("stop_words")).distinct
    val remover = new StopWordsRemover()
      .setStopWords(stopwords)
      .setInputCol("tokens")
      .setOutputCol("clean_tokens")

    val vector = new CountVectorizer()
      .setInputCol(remover.getOutputCol)
      .setOutputCol("features")
//      .setOutputCol("numeric_tokens")

    val pca = new PCA()
      .setK(conf.option[Int](ConfigKeys.PARAM_PCA_K))
      .setInputCol(vector.getOutputCol)
      .setOutputCol("features")

    val classifier = new RandomForestClassifier()
      .setFeaturesCol(vector.getOutputCol)
      .setLabelCol("label")
      .setNumTrees(conf.option[Int](ConfigKeys.PARAM_RF_NTREE))

    // define ml pipeline, where it convert tokens to numeric vector, then create trees with random forest
    val pipeline = new Pipeline().setStages(Array(remover, vector, classifier))
//      .setStages(Array(remover, vector, pca, classifier))

    // do not need define param search for random forest
    val paramGrid = new ParamGridBuilder().build()

    val evaluator = new MulticlassClassificationEvaluator()
      .setLabelCol("label")
      .setPredictionCol("prediction")
      // "f1" (default), "weightedPrecision", "weightedRecall", "accuracy"
      .setMetricName(conf.option[String](ConfigKeys.PARAM_EVAL_METRIC))

    // feed ml pipeline and evaluator to cross validator
    val cv = new CrossValidator()
      .setEstimator(pipeline)
      .setEvaluator(evaluator)
      .setEstimatorParamMaps(paramGrid)
      .setNumFolds(conf.option[Int](ConfigKeys.PARAM_EVAL_NFOLDS))

    // train and return best model
    val model = cv.fit(dft)
    model.bestModel.asInstanceOf[PipelineModel]
  }

  /** Temporary testing with OnevsRest on Logistic regression & kNN */
  private def testTermModel(df:DataFrame)(implicit spark:SparkSession):PipelineModel = {
    import spark.implicits._

    val dft = prefineTokens(df)

    val vector = new HashingTF()
      .setNumFeatures(1000)
      .setInputCol("tokens")
      .setOutputCol("features")

    val pca = new PCA()
      .setK(conf.option[Int](ConfigKeys.PARAM_PCA_K))
      .setInputCol(vector.getOutputCol)
      .setOutputCol("features")

//    val classifier = new KNNClassifier()
//      .setTopTreeSize(train.count().toInt / 500)
//      .setFeaturesCol(vector.getOutputCol)
//      .setPredictionCol("prediction")
//      .setK(1)

    val lr = new LogisticRegression()
      .setLabelCol("tokens")
      .setFeaturesCol(vector.getOutputCol)
    val classifier = new OneVsRest().setClassifier(lr)

    val model = new Pipeline()
      .setStages(Array(vector, pca, classifier))
      .fit(dft)

    // extract tokens from $tokenCount model after fitting
    //val tokens = model.stages(0).asInstanceOf[CountVectorizerModel].vocabulary


//    val pipeline = new Pipeline().setStages(Array(tokenizer, hashingTF, rf))
//
//    val paramGrid = new ParamGridBuilder()
//      .addGrid(lr.regParam, Array(0.1, 0.01))
//      .build()
//
//    val cv = new CrossValidator()
//      .setEstimator(pipeline)
//      .setEvaluator(new BinaryClassificationEvaluator)
//      .setEstimatorParamMaps(paramGrid)
//      .setNumFolds(7)
//
//    val model = cv.fit(trainingData)
//
//    model.transform(testData)
//      .select("id", "label", "prediction")
//      .collect()
//      .foreach { case Row(id:Int, label:Double, prediction:Double) =>
//        println(s"($id) --> label=$label, prediction=$prediction")
//      }
//
//    model.bestModel.asInstanceOf[PipelineModel].write.overwrite().save(conf.getOptionValueByName(ConfigKeys.MODEL).toString)

    model
  }

  /** Extract 2-grams and 3-grams from single tokens, then merges them into original tokens */
  private def prefineTokens(df:DataFrame)(implicit spark:SparkSession):DataFrame = {
    import spark.implicits._

    // get 2-grams and 3-grams for tokens
    val ngram2 = new NGram().setN(2)
      .setInputCol("tokens")
      .setOutputCol("ngrams_2")

    val ngram3 = new NGram().setN(3)
      .setInputCol("tokens")
      .setOutputCol("ngrams_3")

    /*
    // VectorAssembler has not supported ArrayString yet
    val assembler = new VectorAssembler()
      .setInputCols(Array("tokens", "ngrams_2", "ngrams_3"))
      .setOutputCol("tokens")
    */

    // merge 2-grams and 3-grams into original tokens vector
    val mergeTokens = udf {(g1:Seq[String], g2:Seq[String], g3:Seq[String]) => g1 ++ g2 ++ g3 }

    new Pipeline().setStages(Array(ngram2, ngram3))
      .fit(df).transform(df)
      .withColumn("tokens", mergeTokens($"tokens", $"ngrams_2", $"ngrams_3"))
  }

  /** Pipeline to extract brand */
  private def extractBrands(df:DataFrame)(implicit spark:SparkSession):DataFrame = {
    import spark.implicits._

    // filter empty brand rows then split mapping (term -> brands) to (term -> brand)
    df.filter(size($"brands") > 0)
      .withColumn("brand", explode(when($"brands".isNotNull, $"brands").otherwise(array(lit(null).cast("string")))))
      .dropDuplicates(Seq("target", "brand"))
      .select($"target" as "term", $"brand")
  }


  /**
    * Persist models and attributes generated after training
    *
    * @param data result [[ProcessType]] after [[process()]]
    * @param spark session created when starting process
    */
  override def write(data:(PipelineModel, DataFrame, DataFrame))(implicit spark:SparkSession):Unit = {
    val (termModel, brandModel, attrs) = data

    // save models
    termModel.write.overwrite().save(conf.option[String](ConfigKeys.MODEL_TERM))


    // save metadata
    brandModel.write.mode(SaveMode.Overwrite).save(conf.option[String](ConfigKeys.ATTR_BRAND))
    attrs.write.mode(SaveMode.Overwrite).save(conf.option[String](ConfigKeys.ATTR_TERM))
  }
}
