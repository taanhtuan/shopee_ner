package conf

import java.io.{FileInputStream}
import java.net.URI
import java.util.stream.Collectors

import com.typesafe.config.ConfigFactory
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, Path}

import com.typesafe.config.Config

/**
 * Parse settings from configuration file to [[AppConfig]], which will be used in running a specified process
  *
 * @author TuanTA 
 * @since 2017-04-29 22:31
 */
class ArgsConfig(args:Array[String]) {
  import ConfigKeys._

  /** Parsing console arguments and settings in configuration file */
  val parsedArgs = {
    val defaultConf = ArgsConfig.parser.parse(args, AppConfig()) match {
      case Some(config) => config
      case None => AppConfig()
    }

    if (defaultConf.file.isEmpty)
      throw new IllegalArgumentException("Parameters are invalid. Can't find config file")
    // read configs from file
    val appParams = readConfigFromFile(s"${defaultConf.file}")

    val Seq(termModel, posModel, brandAttr, termAttr, inputPath, outputPath) = appParams.io.map {
      case x:FileConfig => x.getPath
      case y:HDFSConfig => y.getPath
    } ++ (if (appParams.io.length == 5) Seq("") else Nil) // train process does not need 'outputPath'

    defaultConf.copy(
      process = appParams.process,
      termModelPath = termModel, posModelPath = posModel,
      brandAttrPath = brandAttr, termAttrPath = termAttr,
      input = inputPath, output = outputPath,
      params = appParams.params
    )
  }

  /** Get master of Spark */
  def getMaster:String = parsedArgs.master

  /** Get name of Spark application */
  def getAppName:String = s"${parsedArgs.process} ${parsedArgs.name}"

  /** Get check point in case of fault tolerance in streaming process, might be use in prediction process */
  def getCheckpointLocation:String = ""

  /** Only be used in case of streaming process */
  def getOutputMode:String = parsedArgs.outputMode

  /**
    * Currently suppor read from file system or HDFS
    * @param path to configuration file
    * @return object contains required parameters for application
    */
  def readConfigFromFile(path:String):AppParams = {
    val f = if (path.startsWith("hdfs")) {
      val pattern = "(hdfs://.+:\\d+)(.+)".r
      val pattern(protocol, file) = path
      val fs = FileSystem.get(new URI(protocol), new Configuration())
      fs.open(new Path(file))
    } else new FileInputStream(path)

    import java.io.BufferedReader
    import java.io.InputStreamReader
    val br = new BufferedReader(new InputStreamReader(f))
    val conf = ConfigFactory.parseString(br.lines().collect(Collectors.joining("\n"))).resolve()

    import AppKeys._
    AppParams(
      if(conf.hasPath(PROCESS)) conf.getString(PROCESS) else "",
      if(conf.hasPath(PARAMETERS)) conf.getConfig(PARAMETERS) else null,
      if(conf.hasPath(INPUT_OUTPUT)) IOConfig.parse(conf.getConfigList(INPUT_OUTPUT)) else Nil
    )
  }

  /** Parse settings in configuration file for the application */
  private def getOptionValueByName(name:String):Any = name match {
    case INPUT => parsedArgs.input
    case OUTPUT => parsedArgs.output
    case PROCESS => parsedArgs.process
    case MODEL_TERM => parsedArgs.termModelPath
    case MODEL_POS => parsedArgs.posModelPath
    case ATTR_BRAND => parsedArgs.brandAttrPath
    case ATTR_TERM => parsedArgs.termAttrPath
    case PARAM_PCA_K => parsedArgs.params.getInt(PARAM_PCA_K)
    case PARAM_RF_NTREE => parsedArgs.params.getInt(PARAM_RF_NTREE)
    case PARAM_EVAL_METRIC => parsedArgs.params.getString(PARAM_EVAL_METRIC)
    case PARAM_EVAL_NFOLDS => parsedArgs.params.getInt(PARAM_EVAL_NFOLDS)
  }

  /**
    * Wrapper of [[getOptionValueByName()]], parses settings in configuration file for the application
    *
    * @param name name of option
    * @tparam T type of option
    * @return value of option if it's found
    */
  def option[T](name:String):T = getOptionValueByName(name).asInstanceOf[T]
}

object ArgsConfig {
  val parser = new scopt.OptionParser[AppConfig]("scopt") {
    opt[String]('n', "name").action((name, config) => config.copy(name = name))
    opt[String]('m', "master").action((master, config) => config.copy(master = master))
    opt[String]('f', "file").action((file, config) => config.copy(file = file))
  }
}

/**
  * Application settings
  *
  * @param name name of Spark application, used for all processes
  * @param master standalone mode or cluster mode
  * @param file path to configuration file
  * @param process name of process (may be Train or Prediction)
  * @param input input data folder
  * @param output ouput data folder
  * @param termModelPath folder stores model after training terms
  * @param posModelPath path to model for pos tagging, using in training and predicting processes
  * @param brandAttrPath folder stores attributes after training brands
  * @param termAttrPath folder stores attributes after generating model
  * @param outputMode streaming output mode, Append by default
  * @param params optional settings, i.e. parameters of machine learning pipeline
  */
case class AppConfig(
  name:String = "Entity Recognition",
  master:String = "",
  file:String = "",
  process:String = ProcessOption.TRAIN,
  input:String = "",
  output:String = "",
  termModelPath:String = "",
  posModelPath:String = "",
  brandAttrPath:String = "",
  termAttrPath:String = "",
  outputMode:String = "Append",
  params:Config = null
)

/** Required config keys */
object AppKeys {
  val PROCESS = "process"
  val INPUT_OUTPUT = "io"
  val PARAMETERS = "params"
}

/** Optional keys in configuration file */
object ConfigKeys {
  val PROCESS = "process"
  val INPUT = "input"
  val OUTPUT = "output"

  // storing model and attributes
  val MODEL_TERM = "model_term"
  val MODEL_POS = "model_pos"
  val ATTR_BRAND = "attr_brand"
  val ATTR_TERM = "attr_term"

  // learning params
  val PARAM_PCA_K = "pca.k"
  val PARAM_RF_NTREE = "rf.num-tree"
  val PARAM_EVAL_METRIC = "evaluation.metric"
  val PARAM_EVAL_NFOLDS = "evaluation.nr-folds"
}
