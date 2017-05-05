package utils

import org.apache.spark.sql.{ForeachWriter, Row}

/**
  *
  * @author TuanTA
  * @since 2017-04-29 23:12
  */
class ConsoleWriter extends ForeachWriter[Row] {

  override def open(partitionId:Long, version:Long):Boolean = true

  override def process(value:Row):Unit = println(value)

  override def close(errorOrNull:Throwable):Unit = {}
}
