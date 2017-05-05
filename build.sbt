import AssemblyKeys._

val commonSettings = Seq(
  organization := "shopee",
  version := "0.1.0",
  scalaVersion := "2.11.8"
)

val sparkVersion = "2.1.0"

lazy val shopee = (project in file("."))
  .enablePlugins(JavaAppPackaging, LauncherJarPlugin)
  .settings(assemblySettings: _*)
  .settings(
    commonSettings,
    name := "entity-recognition",
    resolvers += "Spark Packages Repo" at "http://dl.bintray.com/spark-packages/maven",
    libraryDependencies ++= Seq(
      "com.typesafe" % "config" % "1.3.1",
      "com.github.scopt" %% "scopt" % "3.5.0",
      // nlp
      "edu.stanford.nlp" % "stanford-corenlp" % "3.6.0"
    ) ++ Seq(
//      "org.scalanlp" %% "breeze-natives" % "0.12",
      // spark
      "org.apache.spark" %% "spark-core" % sparkVersion,
      "org.apache.spark" %% "spark-sql" % sparkVersion,
      "org.apache.spark" %% "spark-mllib" % sparkVersion
    )
//    .map(_ % "provided")
    ,
    jarName in assembly := "entity-recognition.jar",
    mainClass in assembly := Some("shopee.Application"),
    mergeStrategy in assembly := {
      case PathList("META-INF", "maven", "commons-logging", "commons-logging", xs @ _*) =>
        MergeStrategy.first
      case x =>
        val oldStrategy = (mergeStrategy in assembly).value
        oldStrategy(x)
    }
  )
