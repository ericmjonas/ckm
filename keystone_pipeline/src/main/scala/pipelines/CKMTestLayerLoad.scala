package pipelines

import breeze.linalg._
import breeze.numerics._
import breeze.stats.distributions._
import breeze.stats.{mean, median}
import evaluation.{AugmentedExamplesEvaluator, MulticlassClassifierEvaluator}
import loaders._
import nodes.images._
import nodes.learning._
import nodes.stats.{StandardScaler, Sampler, SeededCosineRandomFeatures, BroadcastCosineRandomFeatures, CosineRandomFeatures}
import nodes.util.{Identity, Cacher, ClassLabelIndicatorsFromIntLabels, TopKClassifier, MaxClassifier, VectorCombiner}
import org.apache.spark.Accumulator
import workflow.Transformer

import org.apache.commons.math3.random.MersenneTwister
import org.apache.log4j.Level
import org.apache.log4j.Logger
import org.apache.spark.rdd.RDD
import org.apache.spark.{SparkConf, SparkContext}
import pipelines.Logging
import scopt.OptionParser
import utils.{Image, MatrixUtils, Stats, ImageMetadata, LabeledImage, RowMajorArrayVectorizedImage, ChannelMajorArrayVectorizedImage}
import workflow.Pipeline

import org.yaml.snakeyaml.constructor.Constructor
import org.yaml.snakeyaml.Yaml
import scala.reflect.{BeanProperty, ClassTag}

import CKMImageNetTest.loadModel
import java.io.{File, BufferedWriter, FileWriter}

object CKMImageNetTestLoadLayer extends Serializable with Logging {
  val appName = "CKMImageNetTestLoadLayer"

  def run(sc: SparkContext, conf: CKMConf) {
    println("RUNNING CKMImageNetTestLoadLayer")
    val oldFeatureId = conf.seed + "_" + conf.dataset + "_" +  conf.expid  + "_" + (conf.layerToLoad+1) + "_" + conf.patch_sizes.slice(0,conf.layerToLoad+1).mkString("-") + "_" + conf.bandwidth.slice(0,conf.layerToLoad+1).mkString("-") + "_" + conf.pool.slice(0,conf.layerToLoad+1).mkString("-") + "_" + conf.poolStride.slice(0,conf.layerToLoad+1).mkString("-") + "_" + conf.filters.slice(0,conf.layerToLoad+1).mkString("-")
    println(s"OLD FEATURE ID: ${oldFeatureId}")

    val featureId = conf.seed + "_" + conf.dataset + "_" +  conf.expid  + "_" + conf.layers + "_" + conf.patch_sizes.mkString("-") + "_" + conf.bandwidth.mkString("-") + "_" + conf.pool.mkString("-") + "_" + conf.poolStride.mkString("-") + "_" + conf.filters.mkString("-")

    var data = loadTest(sc, conf, oldFeatureId,  conf.featureDir, conf.labelDir)

    val currLayer = conf.layerToLoad
    println("BLAS TEST")
    val x = DenseMatrix.rand(100,100)
    val y = x*x

    var convKernel: Pipeline[Image, Image] = new Identity()
    implicit val randBasis: RandBasis = new RandBasis(new ThreadLocalRandomGenerator(new MersenneTwister(conf.seed)))
    val gaussian = new Gaussian(0, 1)
    val uniform = new Uniform(0, 1)
    var numOutputFeatures = conf.filters(currLayer +1)

    val numTest = data.count()

    val (xDim, yDim, numChannels) = getInfo(data)
    println(s"Info ${xDim}, ${yDim}, ${numChannels}")

    var numInputFeatures = numChannels
    var currX = xDim
    var currY = yDim
    println(s"${currX}x${currY}x${numInputFeatures} images")
    var accs: List[Accumulator[Double]] = List()
    var pool_accum = sc.accumulator(0.0)

    val patchSize = math.pow(conf.patch_sizes(currLayer + 1), 2).toInt
    val seed = conf.seed
    val ccap = new CC(numInputFeatures*patchSize, numOutputFeatures,  seed, conf.bandwidth(currLayer + 1), currX, currY, numInputFeatures, sc, None, conf.whitenerOffset, conf.pool(currLayer + 1), true, false)
    accs =  ccap.accs
    var pooler =  new MyPooler(conf.poolStride(currLayer + 1), conf.pool(currLayer + 1), identity, (x:DenseVector[Double]) => mean(x), sc)
    pool_accum = pooler.pooling_accum
    convKernel = convKernel andThen ccap andThen pooler

    currX = math.ceil(((currX  - conf.patch_sizes(currLayer + 1) + 1) - conf.pool(currLayer + 1)/2.0)/conf.poolStride(currLayer + 1)).toInt
    currY = math.ceil(((currY  - conf.patch_sizes(currLayer + 1) + 1) - conf.pool(currLayer + 1)/2.0)/conf.poolStride(currLayer + 1)).toInt

    numInputFeatures = numOutputFeatures
    val outFeatures = currX * currY * numOutputFeatures
    println(s"Layer 1 output, Width: ${currX}, Height: ${currY}")
    println("OUT FEATURES " +  outFeatures)

    val featurizer = ImageExtractor andThen convKernel andThen ImageVectorizer andThen new Cacher[DenseVector[Double]]
    val convTestBegin = System.nanoTime
    var XTest = featurizer(data)
    val count = XTest.count()
    val convTestTime  = timeElapsed(convTestBegin)
    println(s"Generating test features took ${convTestTime} secs")

    println(s"Per image metric breakdown (for last layer):")
    accs.map(x => println(x.name.get + ":" + x.value/(count)))
    println(pool_accum.name.get + ":" + pool_accum.value/(count))
    println("Total Time (for last layer): " + (accs.map(x => x.value/(count)).reduce(_ + _) +  pool_accum.value/(count)))

    println(s"count: ${count}")

    val labelVectorizer = ClassLabelIndicatorsFromIntLabels(conf.numClasses)
    println(conf.numClasses)
    println("VECTORIZING LABELS")

    val yTest = labelVectorizer(LabelExtractor(data))

    if (conf.saveFeatures) {
      println("Saving Features")
      XTest.zip(LabelExtractor(data)).map(xy => xy._1.map(_.toFloat).toArray.mkString(",") + "," + xy._2).saveAsTextFile(
        s"${conf.featureDir}ckn_${featureId}_test_features")
    }
    if (conf.solve) {

      val model = loadModel(featureId, conf.modelDir, conf)
      val testPredictions = model.apply(XTest).cache()

      val yTestPred = MaxClassifier.apply(testPredictions)

      val top1TestActual = TopKClassifier(1)(yTest)
      if (conf.numClasses >= 5) {
        val top5TestPredicted = TopKClassifier(5)(testPredictions)
        println("Top 5 test acc is " + (100 - Stats.getErrPercent(top5TestPredicted, top1TestActual, testPredictions.count())) + "%")
      }

      val top1TestPredicted = TopKClassifier(1)(testPredictions)
      println("Top 1 test acc is " + (100 - Stats.getErrPercent(top1TestPredicted, top1TestActual, testPredictions.count())) + "%")

      println("Saving model")
      val xs = model.xs.zipWithIndex
      xs.map(mi => breeze.linalg.csvwrite(new File(s"${conf.modelDir}/${featureId}.model.weights.${mi._2}"), mi._1, separator = ','))
      model.bOpt.map(b => breeze.linalg.csvwrite(new File(s"${conf.modelDir}/${featureId}.model.intercept"),b.toDenseMatrix, separator = ','))
    }
  }

  def loadTest(sc: SparkContext, conf: CKMConf, featureId: String, dataRoot: String = "/", labelsRoot: String = "/"): RDD[LabeledImage] = {
    CKMLayerLoader(sc, conf.layerToLoad, featureId, conf, Some(conf.numClasses)).test
  }

  def timeElapsed(ns: Long) : Double = (System.nanoTime - ns).toDouble / 1e9

  def getInfo(data: RDD[LabeledImage]): (Int, Int, Int) = {
    val image = data.take(1)(0).image
    (image.metadata.xDim, image.metadata.yDim, image.metadata.numChannels)
  }

  /**
   * The actual driver receives its configuration parameters from spark-submit usually.
   * @param args
   */
  def main(args: Array[String]) = {

    if (args.size < 1) {
      println("Incorrect number of arguments...Exiting now.")
    } else {
      val configfile = scala.io.Source.fromFile(args(0))
      val configtext = try configfile.mkString finally configfile.close()
      println(configtext)
      val yaml = new Yaml(new Constructor(classOf[CKMConf]))
      val appConfig = yaml.load(configtext).asInstanceOf[CKMConf]
      val conf = new SparkConf().setAppName(appConfig.expid)
      // Logger.getLogger("org").setLevel(Level.WARN)
      // Logger.getLogger("akka").setLevel(Level.WARN)

      // NOTE: ONLY APPLICABLE IF YOU CAN DONE COPY-DIR
      conf.remove("spark.jars")

      conf.setIfMissing("spark.master", "local[16]")
      conf.set("spark.driver.maxResultSize", "0")
      val featureId = appConfig.seed + "_" + appConfig.dataset + "_" +  appConfig.expid  + "_" + appConfig.layers + "_" + appConfig.patch_sizes.mkString("-") + "_" + appConfig.bandwidth.mkString("-") + "_" + appConfig.pool.mkString("-") + "_" + appConfig.poolStride.mkString("-") + "_" + appConfig.filters.mkString("-")
      conf.setAppName(featureId)
      val sc = new SparkContext(conf)
      //sc.setCheckpointDir(appConfig.checkpointDir)
      run(sc, appConfig)
      sc.stop()
    }
  }
}
