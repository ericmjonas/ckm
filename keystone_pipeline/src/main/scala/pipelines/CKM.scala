package pipelines.images.cifar

import breeze.linalg._
import breeze.numerics._
import evaluation.MulticlassClassifierEvaluator
import loaders.CifarLoader
import nodes.images._
import nodes.learning.{BlockLeastSquaresEstimator, ZCAWhitener, ZCAWhitenerEstimator}
import nodes.stats.{StandardScaler, Sampler}
import nodes.util.{Cacher, ClassLabelIndicatorsFromIntLabels, MaxClassifier}
import org.apache.spark.{SparkConf, SparkContext}
import pipelines.Logging
import scopt.OptionParser
import utils.{MatrixUtils, Stats}


import scala.reflect.BeanProperty
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.Constructor

object CKM extends Serializable with Logging {
  val appName = "CKM"

  def run(sc: SparkContext, conf: CKMConf) {
    println(s"Num Layers is: ${conf.layers}, filters is: ${conf.filters}, bandwidth is: ${conf.bandwidth}, patch_sizes are ${conf.patch_sizes}")
  }

  class CKMConf {
    @BeanProperty var  layers: Int = 1
    @BeanProperty var  filters: Array[Int] = Array(1)
    @BeanProperty var  bandwidth : Array[Double] = Array(1.8)
    @BeanProperty var  patch_sizes: Array[Int] = Array(5)
  }

  /**
   * The actual driver receives its configuration parameters from spark-submit usually.
   * @param args
   */
  def main(args: Array[String]) = {

    val configfile = scala.io.Source.fromFile(args(0))
    val configtext = try configfile.mkString finally configfile.close()

    val yaml = new Yaml(new Constructor(classOf[CKMConf]))
    val appConfig = yaml.load(configtext).asInstanceOf[CKMConf]

    val appName = s"CKM"
    val conf = new SparkConf().setAppName(appName)
    conf.setIfMissing("spark.master", "local[16]")
    val sc = new SparkContext(conf)
    run(sc, appConfig)
    sc.stop()
  }
}