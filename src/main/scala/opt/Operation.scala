package opt

import org.apache.spark.sql._

/**
  * Interface for a process
  *
  * @author TuanTA
  * @since 2017-04-29 19:08
  */
trait Operation {
  /** Predefined return type of [[read()]] */
  protected type InputType

  /** Predefined return type of [[transform()]] */
  protected type TransformType

  /** Predefined return type of [[process()]] */
  protected type ProcessType


  /** Define name of Spark application */
  def appName:String

  /** Define master of Spark */
  def master:String

  /** Turn it on when you want to auto-parse types of columns of an arbitrary CSV file without using
    * [[org.apache.spark.sql.types.StructField]]  */
  def inferSchema:Boolean = true

  /** Generate Spark session for a process */
  def getSparkSession:SparkSession = {
    val sparkBuilder = SparkSession.builder()
      .appName(appName)
      .config("spark.sql.streaming.schemaInference", inferSchema)
    master match {
      case "" => sparkBuilder.getOrCreate()
      case master:String => sparkBuilder.master(master).getOrCreate()
    }
  }

  /**
    * Read data from datasource
    *
    * @param spark session created when starting process
    * @return [[InputType]], it must be specified in implemented class
    */
  def read()(implicit spark:SparkSession):InputType

  /**
    * Received data [[InputType]] from [[read()]], transform raw data to features for training or predicting process.
    *
    * @param data original data
    * @param spark session created when starting process
    * @return [[TransformType]], it must be specified in implemented class
    */
  def transform(data:InputType)(implicit spark:SparkSession):TransformType

  /**
    * Learn process or Predicting process, Received data [[TransformType]] from [[transform()]]
    *
    * @param data features after transforming
    * @param spark session created when starting process
    * @return [[ProcessType]], it must be specified in implemented class
    */
  def process(data:TransformType)(implicit spark:SparkSession):ProcessType

  /**
    * It's a sink, used to write out final result
    *
    * @param data result [[ProcessType]] after [[process()]]
    * @param spark session created when starting process
    */
  def write(data:ProcessType)(implicit spark:SparkSession):Unit = ???

  /**
    * Create application and run pipeline
    *
    * @return created spark session for further purposes
    */
  def buildApp():SparkSession = {
    implicit val spark = getSparkSession
    write(process(transform(read())))
    spark
  }
}
