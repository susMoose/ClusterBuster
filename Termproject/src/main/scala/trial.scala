import org.apache.commons.math3.distribution.TDistribution
import org.apache.spark._
import org.apache.spark.sql.expressions._
import org.apache.spark.sql.expressions.Window
import org.apache.spark.sql.functions._
import org.apache.spark.ml.feature.VectorAssembler
import org.apache.spark.ml.linalg.Matrix
import org.apache.spark.ml.stat.{ChiSquareTest, Correlation}
import org.apache.spark.sql.{Column, DataFrame, SparkSession}

object trial {
  val ROWS_AHEAD = 10
  val ROWS_BEHIND = 3

  def main(args: Array[String]) {
    val tweetFile = args(0)
    val stockFile = args(1)
    val outputFile = args(2)

    val spark = SparkSession.builder().appName("TrialByFire").getOrCreate()
    //if using shell instead of the below line, use :   val tweetData = spark.read.json("hdfs:///twitter/2016/01/01/00")
    val tweetData = spark.read.json(tweetFile)
    // getting company names
    val nameData  = spark.read.csv("hdfs:///companys/comps2.csv")
    // Only getting two components of a tweet
    var tweetColumns = tweetData.select("created_at", "text")

    // The below two lines are removing tweets that do not contain dates/text values
    tweetColumns  = tweetColumns.filter(_(0)!= null).filter(_(1) !=null)

    val stocks = getStocks(spark, stockFile)
    val dataFrame = spark.read.json(tweetFile)

    val contained = udf{(tweet:String, name:String, nickName: String) => {
      val tweetWords = tweet.split(" |\\.|#")
      if(nickName!= null) {
        val nicks = nickName.split(", ")
        tweetWords.exists(_.contains(name)) || tweetWords.exists(word =>{ nicks.exists(n => n.equals(word))})
      }
      else  tweetWords.exists(_.contains(name))
    }}


    val dfs = tweetColumns.join( nameData, contained(tweetColumns("text"), nameData("_c1"),nameData("_c2")))

    if (stocks.count() > 0) {
      stocks.write.csv(outputFile)
    }
  }

  /**
    * Constructs a new DataFrame containing rows which have the following:
    * Stock, Date, Open, High, Low, Close, Volume, OpenInt, diff_0...diff_10, prev_avg
    * @param spark SparkSession to use
    * @param stockSource Path to the stock data files
    * @return
    */
  def getStocks(spark: SparkSession, stockSource: String) : DataFrame = {
    import spark.implicits._
    val csv = spark.read.format("csv").option("header", true).load(stockSource)
    // Convert file name to stock name
    //
    val mapName = udf((fileName: String) => fileName.substring(fileName.lastIndexOf('/')+1, fileName.indexOf('.')).toUpperCase)
    // Add a new column with the stock name
    var withWindows = csv.withColumn("Stock", mapName(input_file_name()))

    val window = Window.partitionBy($"Stock").orderBy($"Date")
    val prevWindow = window.rowsBetween(Window.currentRow - ROWS_BEHIND, Window.currentRow)
    withWindows = withWindows.withColumn("prev_avg", avg($"Close").over(prevWindow))
    for (i <- 0 to ROWS_AHEAD) {
      val relative = lead(csv("Close"), i).over(window)
      val computeDiff = udf((current: Double, future: Double) => {
        Option[Double](future).map(_ - current).getOrElse(0D)
      })
      // Difference between previous average and the i-th away day
      withWindows = withWindows.withColumn("diff_"+i, computeDiff($"prev_avg", relative))
    }


    withWindows
  }

  case class Stats(pValue: Double, dof: Double, correlation: Double)

  /**
    * Performs a spearman correlation test to see if the independent variable is monotonically correlated with the specified dependent variables.
    *
    * @param data
    * @param independent the column to use as the independent variable
    * @param dependent sequence of potentially dependent columns
    * @return a mapping of dependent variable => stats about the correlation
    */
  def testColumns(data: DataFrame, independent: String, dependent: String*) : Map[String, Stats] = {
    val featureArray = (dependent ++ independent).toArray[String]
    val assembler = new VectorAssembler()
        .setInputCols(featureArray)
        .setOutputCol("features")
    val map = scala.collection.immutable.HashMap.newBuilder[String, Stats]
    // Is there a way to avoid this pass over the data just to count it?
    val count = data.count()
    // Construct a correlation matrix between all variables.  We only care about the pairs of independent with a dependent variable though
    val matrix = Correlation.corr(assembler.transform(data), "features",  "spearman").first().getAs[Matrix](0)
    // We add the independent variable last
    val independentIndex = featureArray.length - 1
    // Look up the correlation coefficients
    for ((col, i) <- featureArray.zipWithIndex) {
      // If it's one we actually care about
      if (col != independent) {
        // Math from https://en.wikipedia.org/wiki/Spearman%27s_rank_correlation_coefficient#Determining_significance
        val dof = count - 2
        val rho =  matrix(independentIndex, i)
        val t = rho * Math.sqrt(dof / (1 - (rho * rho)))
        // This is based on Spark's implementation of a TTest using org.apache.commons.math3.stat.inference.TTest
        val distribution = new TDistribution(null, dof)
        // Isn't the p value the same regardless of whether it's negative.  Also, 2.0 because it's two sided?  Is that right?
        val p = 2.0 * distribution.cumulativeProbability(-t)
        map.+=((col, Stats(p, dof, rho)))
      }
    }
    map.result()
  }
}