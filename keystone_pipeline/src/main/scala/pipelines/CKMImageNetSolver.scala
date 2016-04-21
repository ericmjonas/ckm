package pipelines

import breeze.linalg._
import breeze.numerics._
import breeze.stats.distributions._
import breeze.stats.{mean, median}
import evaluation.{AugmentedExamplesEvaluator, MulticlassClassifierEvaluator}
import loaders._
import nodes.images._
import nodes.learning._
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
import utils.{Image, MatrixUtils, Stats}
import workflow.Pipeline

import org.yaml.snakeyaml.constructor.Constructor
import org.yaml.snakeyaml.Yaml
import scala.reflect.{BeanProperty, ClassTag}

import java.io.{File, BufferedWriter, FileWriter}
import CKMImageNetTest.loadModel

object CKMImageNetSolver extends Serializable with Logging {
  val appName = "CKMImageNetSolver"

  def run(sc: SparkContext, conf: CKMConf) {
    println("RUNNING CKMImageNetSolver")
    val featureId = conf.seed + "_" + conf.dataset + "_" +  conf.expid  + "_" + conf.layers + "_" + conf.patch_sizes.mkString("-") + "_" + conf.bandwidth.mkString("-") + "_" + conf.pool.mkString("-") + "_" + conf.poolStride.mkString("-") + "_" + conf.filters.mkString("-")

    println("BLAS TEST")
    val x = DenseMatrix.rand(100,100)
    val y = x*x

    println(featureId)
    val featurized = CKMFeatureLoader(sc, conf.featureDir, featureId)
    val labelVectorizer = ClassLabelIndicatorsFromIntLabels(conf.numClasses)
    println(conf.numClasses)
    println("VECTORIZING LABELS")

    val yTrain = labelVectorizer(featurized.yTrain)
    val yTest = labelVectorizer(featurized.yTest)
    val XTrain = featurized.XTrain
    val XTest = featurized.XTest
    var model = new BlockLeastSquaresEstimator(conf.blockSize, conf.numIters, conf.reg).fit(XTrain, yTrain)

    println("Training finish!")

    val trainPredictions = model.apply(XTrain).cache()
    val yTrainPred = MaxClassifier.apply(trainPredictions)

    val top1TrainActual = TopKClassifier(1)(yTrain)
    if (conf.numClasses >= 5) {
      val top5TrainPredicted = TopKClassifier(5)(trainPredictions)
      println("Top 5 train acc is " + (100 - Stats.getErrPercent(top5TrainPredicted, top1TrainActual, trainPredictions.count())) + "%")
    }

    val top1TrainPredicted = TopKClassifier(1)(trainPredictions)
    println("Top 1 train acc is " + (100 - Stats.getErrPercent(top1TrainPredicted, top1TrainActual, trainPredictions.count())) + "%")

    val testPredictions = model.apply(XTest).cache()

    val yTestPred = MaxClassifier.apply(testPredictions)

    val numTestPredict = testPredictions.count()
    println("NUM TEST PREDICT " + numTestPredict)

    val top1TestActual = TopKClassifier(1)(yTest)
    if (conf.numClasses >= 5) {
      val top5TestPredicted = TopKClassifier(5)(testPredictions)
      println("Top 5 test acc is " + (100 - Stats.getErrPercent(top5TestPredicted, top1TestActual, numTestPredict)) + "%")
    }

    val top1TestPredicted = TopKClassifier(1)(testPredictions)
    println("Top 1 test acc is " + (100 - Stats.getErrPercent(top1TestPredicted, top1TestActual, testPredictions.count())) + "%")
  }

  def timeElapsed(ns: Long) : Double = (System.nanoTime - ns).toDouble / 1e9

  def modelEqual(model1: BlockLinearMapper, model2: BlockLinearMapper) = {
    val xsEqual = model1.xs.zip(model2.xs).map(x => all(x._1 :== x._2)).reduce(_ && _)
    val bOptEqual = model1.bOpt.flatMap(x => model2.bOpt.map(y => all(x :== y))).getOrElse(true)
    val blockSizeEqual = model1.blockSize == model2.blockSize
    xsEqual && bOptEqual && blockSizeEqual
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
