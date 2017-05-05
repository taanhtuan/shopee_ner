package conf

import java.util

import com.typesafe.config.Config

/**
  * Interface contains settings for IO configuration. An IO configuration may be read from File system, DB, etc. should
  * be extended functions from this interface.
  *
  * @author TuanTA
  * @since 2017-04-29 20:14
  */
trait IOConfig {
  val name:String = getOption(CONFIG_KEY_NAME).toString
  def protocol:String
  protected def options:Map[String, Any]

  private val CONFIG_KEY_NAME = "name"
  private val CONFIG_KEY_PROTOCOL = "protocol"

  /** Check if config is satisfied required option */
  protected def require:Seq[String] = Seq(CONFIG_KEY_NAME, CONFIG_KEY_PROTOCOL)

  /** List of keys will be used in configuration */
  protected def keyOptions:Seq[String] = Seq(CONFIG_KEY_NAME)

  /** Check if given settings in config are satisfied requirement */
  def verify(config:Config):Boolean = {
    import scala.collection.JavaConversions._
    require.forall(config.entrySet().map(_.getKey).contains) && this.protocol == config.getString(CONFIG_KEY_PROTOCOL)
  }

  /** Parse settings of a configuration */
  protected def parseOptions(config:Config):Map[String, Any] = {
    import scala.collection.JavaConversions._
    config.entrySet().map(_.getKey).filter(keyOptions.contains)
      .map(key => key -> config.getString(key)).toMap
  }

  def parse(config:Config):IOConfig

  protected def getOption(key:String):Option[Any] = options.get(key)
}

object IOConfig {
  private var defaultConfig:Seq[IOConfig] = Seq(FileConfig(), HDFSConfig())

  /**
    * Parse list of IO config in application's configuration file.
    * A config is a specified IO config, where it store list of related settings to it's config type.
    *
    * @param configs list of IO settings
    * @return list of configurations
    */
  def parse(configs:util.List[_ <:Config]):Seq[IOConfig] = {
    import scala.collection.JavaConversions._
    configs.toList.foldLeft(Seq[IOConfig]()) {
      (seq, conf) =>
        defaultConfig.find(_.verify(conf)) match {
          case Some(c) => seq :+ c.parse(conf)
          case None => seq
        }
    }
  }

  def extendConfigs(extensions:Seq[IOConfig]):Unit = defaultConfig = defaultConfig ++ extensions
}

/**
  * Implementation of configuration reader from File system
  *
  * @param protocol file:///
  * @param options list of belonged options related to File system
  */
case class FileConfig(
  protocol:String = "file",
  options:Map[String, Any] = Map[String, Any]()
) extends IOConfig {
  import FileConfig._

  /** File system settings must contain PATH */
  override def require:Seq[String] = super.require ++ Seq(PATH)
  override protected def keyOptions = super.keyOptions ++ Seq(PATH)

  def getPath:String = s"$protocol://${getOption(PATH).get}"

  override def parse(config:Config):IOConfig = {
    FileConfig(options = parseOptions(config))
  }
}

/** Key options */
object FileConfig {
  val PATH = "path"
}


/**
  * Implementation of configuration reader from HDFS
  *
  * @param protocol hdfs://
  * @param options list of belonged options related to HDFS
  */
case class HDFSConfig(
  protocol:String = "hdfs",
  options:Map[String, Any] = Map[String, Any]()
) extends IOConfig {
  import HDFSConfig._

  /** HDFS settings must contain HOST, PORT, PATH */
  override protected def require = super.require ++ Seq(HOST, PORT, PATH)
  override protected def keyOptions:Seq[String] = super.keyOptions ++ Seq(HOST, PORT,PATH)

  override def parse(config:Config):IOConfig = {
    HDFSConfig(options = parseOptions(config))
  }

  private lazy val URL = s"$protocol://${getOption(HOST).get}:${getOption(PORT).get}"

  def getPath:String = s"$URL${getOption(PATH).get}"
}

/** Key options */
object HDFSConfig {
  val HOST = "host"
  val PORT = "port"
  val PATH = "path"
}
