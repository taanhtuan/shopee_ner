USAGE
=====

This section describes the way to setup environments and build the project before executing processes.

Basically, there are 2 processes:

1. Training process: is to build model from raw data which is stored in `data/train` folder
2. Predicting process: is to apply learned model to predcit test data, stored in `data/test` folder

All the configurations which are used to run processes are stored in `conf`

I supported 2 ways to run processes (they generate same results):

1. Development mode: where you can use run directly via sbt
2. Cluster mode: where you put file `entity-recognition.jar` after packaging and all necessary `data` folder and `conf`
                 folder on HDFS



[0] __Prerequisites__
---------------------

### POS Tagger model
[Download model here](https://gate.ac.uk/wiki/twitter-postagger.html), then place it at `data/models` folder or
specified path in configuration file. For additional detail, [visit here](https://nlp.stanford.edu/software/tagger.shtml).


### Sbt
The project are written on Scala, and it needs [SBT](http://www.scala-sbt.org/download.html) to compile the code. 
Please make sure it will be installed and can be accessed globally as `./sbt`.


### Spark
The project are written on Spark 2.1.0, please make sure Spark has already deployed, otherwise please 
[visit here](http://spark.apache.org/downloads.html) to download and follow the instruction to install.


### HDFS (optional)
In case you want to run application on Spark at cluster mode, you need HDFS to store `data` and `conf` files in order
to help application can find them during performing process. If you have not set up Hadoop yet, please 
[visit here](http://hadoop.apache.org/releases.html) to download. The code works with Hadoop version 2.7.x, the other
versions are not tested.



[1] __Data structure__
----------------------

We have totally 5 folders in `data` folder: 
1. `train` contains all training data, in CSV format and same CSV structure. It stores multiple CSV files
2. `test` contains test data and stores multiple CSV files
3. `models` will be generated after running Train process, the Predict process only executes when this folder is 
            existed. The name of folder can be changed due to settings in configuration file.
4. `attr` will be generated aside with `model` after training process, and it's required to be existed before
          running Predict process. The name of folder can also be changed due to settings in configuration file.
5. `result` will be generated after predicting process. It contains result of prediction in CSV files.


The training CSV file must be following structure, and the columns must in defined order:

| product_name | core_terms   | brand | stop_words   | others | 
| ------------ |:------------:|:-----:|:------------:|-------:|
| train title  | term1 term 2 | abc   | word1, word2 | any    |


The test CSV file must be following structure:

| product_name |
| ------------ |
| test title   |



[2] __Configuration structure__
-------------------------------

A configuration file must contain 3 parts:

1. `process` defines name of process you want to run, in our case it is `train` or `predict`
2. `io` contains list of places where they stores files for running a process
3. `param` is optional list of settings, be used in tuning model of `train` process

The format of configuration file is followed format of **typesafe config**

```
process = "name of process"

io = [
  {
    name = "name of place"
    protocol = "file or hdfs"
    path = "path to file or folder"
  },
  ...
]

param {
  option1 = 'value 1'
  ...
}
```

Here is an example of a configuration file [train process on file system](../conf/train_fs.conf)

```
# define name of process, supported: 'train' or 'predict'
process = "name of process"

dataDir = "/tmp/shopee/data/"

# define places storing data when running process
# the list must be in order:
# 1. the place storing predicting term model after training
# 2. the place storing POS model for predict process
# 3 & 4. the place storing attributes after training
# 5. the place storing input for 'train' or 'predict' process
# 6. the place storing output for 'predict' process
io = [
  {
    name = "term_model"
    protocol = "file"
    path = ${dataDir}"models/term"
  },
  {
    name = "pos_model"
    protocol = "file"
    path = ${dataDir}"models/gate-EN-twitter.model"
  },
  {
    name = "brand_attribute"
    protocol = "file"
    path = ${dataDir}"attr/brand"
  },
  {
    name = "term_attribute"
    protocol = "file"
    path = ${dataDir}"attr/term"
  },
  {
    name = "input"
    protocol = "file"
    path = ${dataDir}"train/*.csv"
  },
  {
    name = "ouput"
    protocol = "file"
    path = ${dataDir}"result"
  }
]

# optional, used for train process
params {
  max-iter = 10
  regulation = 0.3
}
```

Or you can refer to [train process on HDFS](../conf/train_hdfs.conf) to change configuration in reading and writing
data on HDFS:

```
... # same above

# variables: common settings for io
hdfs = {
  host = "ip to master of hadoop"
  port = "port used for master of hadoop"
  protocol = "hdfs"
}
dataDir = "/shopee/data/" 

# append above settings with common settings 'hdfs'
# as following setting, model will be stored at: hdfs://master_ip:master_port/shopee/data/model
io = [
  ${hdfs} {
    name = "model"
    path = ${dataDir}"models"
  },
  ... # same
]

... # same above
```



[3] __Run in development mode__
-------------------------------

Assume the project store at `/tmp/shoppe`, and all configuration are be set to store on file system at
`/tmp/shopee/data`

Find line 30  in `build.sbt` as below, and comment it to let project loads dependencies during running:

```scala
30. //.map(_ % "provided")
```

*NOTE: please remember to uncomment this line when running in `cluster mode`, otherwise you gonna waste 
more space for storing.*


#### Train process
To run training process:

```bash
./sbt "run -m local[*] -f /tmp/shopee/conf/train_fs.conf"
```

Check if `/tmp/shoppe/data/models` and `/tmp/shoppe/data/attributes` are generated after the process is completed.


#### Predict process
To run predicting process:

```bash
./sbt "run -m local[*] -f /tmp/shopee/conf/predict_fs.conf"
```

When process is completed, please check if `/tmp/shoppe/data/result` are generated for result.



[4] __Run in cluster mode__
---------------------------

### Prepare data and configuration

Please follow the instruction of [data structure](##Data-structure) in section (1) and
[config structure](##Configuration-structure) in section (2) to generate necessary files.


### Build project 

Find line 30  in `build.sbt` as below, and uncomment it to let project ignores dependencies during packaging:

```scala
30. .map(_ % "provided")
```

Use following command:

```bash
cd path_to_prj
./sbt assembly
```

Then check if file `/tmp/shopee/target/entity-recognition.jar` is generated when packaging process is completed.


### Upload to HDFS

Upload data, config and jar file to HDFS:

```bash
# create folder on HDFS to store all files
./hadoop fs -mkdir hdfs://{master_ip}:{master_port}/shopee

# copy data to HDFS
./hadoop fs -copyFromLocal -f /tmp/shopee/data hdfs://{master_ip}:{master_port}/shopee/

# copy config files to HDFS
./hadoop fs -copyFromLocal -f /tmp/shopee/conf hdfs://{master_ip}:{master_port}/shopee/

# copy jar file to HDFS
./hadoop fs -copyFromLocal -f /tmp/shopee/target/entity-recognition.jar hdfs://{master_ip}:{master_port}/shopee/

# change access modes
./hadoop fs -chmod 711 hdfs://{master_ip}:{master_port}/shopee/entity-recognition.jar
```

Please make sure you have right permissions to access and execute files on HDFS.


#### Train process

To run training process:

```
./spark-submit \
  --driver-cores 4 --driver-memory 8G --executor-memory 20G \
  --class "shopee.Application" --master spark://{cluster_ip}:{cluster_port} \
  --deploy-mode cluster \
  hdfs://{master_ip}:{master_port}/shopee/entity-recognition.jar \
  -f hdfs://172.28.50.5:7000/shopee/conf/train_hdfs.conf
```


#### Predict process

To run predicting process:

```
./spark-submit \
  --driver-cores 4 --driver-memory 8G --executor-memory 20G \
  --class "shopee.Application" --master spark://{cluster_ip}:{cluster_port} \
  --deploy-mode cluster \
  hdfs://{master_ip}:{master_port}/shopee/entity-recognition.jar \
  -f hdfs://172.28.50.5:7000/shopee/conf/predict_hdfs.conf
```
