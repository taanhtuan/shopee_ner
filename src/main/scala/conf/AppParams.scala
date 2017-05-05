package conf

import com.typesafe.config.Config

/**
  * A general structure for a configuration file
  *
  * @param process name of process, used to determine which class will be invoked when starting process
  * @param params optional parameters for a process
  * @param io list of io settings when running a process
  *
  * @author TuanTA
  * @since 2017-04-29 19:47
  */
case class AppParams(process:String, params:Config, io:Seq[IOConfig])
