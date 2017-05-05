package shopee

import conf.{ArgsConfig, ConfigKeys}
import opt.Operation
import shopee.recognition.entity.POS

/**
  *
  * @author TuanTA
  * @since 2017-04-29 19:57
  */
object Application {

  def main(args:Array[String]):Unit = {

    val config = new ArgsConfig(args)

    // init POS model
    POS.init(config.option[String](ConfigKeys.MODEL_POS))

    // create process
    val process = config.option[String](ConfigKeys.PROCESS)
    val opt:Operation = getProcess(process, config)

    opt.buildApp()
  }
}
