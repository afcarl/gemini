package tech.sourced.featurext

import gopkg.in.bblfsh.sdk.v1.uast.generated.Node
import io.grpc.ManagedChannelBuilder
import org.apache.spark.internal.Logging
import org.apache.spark.sql.SparkSession
import tech.sourced.featurext.generated.service.FeatureExtractorGrpc.FeatureExtractor
import tech.sourced.featurext.generated.service._
import org.slf4j.{Logger => Slf4jLogger}
import tech.sourced.gemini.util.MapAccumulator

import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.{Duration, SECONDS}
import scala.util.control.NonFatal


sealed abstract class Extractor {
  val Threshold = 5
  def extract(client: FeatureExtractor, uast: Node): Future[FeaturesReply]
  def extractFeatures(client: FeatureExtractor, uast: Node): Future[Seq[Feature]] = {
    extract(client, uast).map(_.features) //avoids duplication of FeaturesReply->Feature code
  }
}

case class IdentifiersExt(weight: Int, split: Boolean) extends Extractor {
  override def extract(client: FeatureExtractor, uast: Node): Future[FeaturesReply] = {
    val req = IdentifiersRequest(
      uast = Some(uast),
      options = Some(IdentifiersOptions(docfreqThreshold = Threshold, weight = weight, splitStem = split))
    )
    client.identifiers(req)
  }
}

case class GraphletExt(weight: Int) extends Extractor {
  override def extract(client: FeatureExtractor, uast: Node): Future[FeaturesReply] = {
    val req = GraphletRequest(
      uast = Some(uast),
      options = Some(GraphletOptions(docfreqThreshold = Threshold, weight = weight))
    )
    client.graphlet(req)
  }
}

case class LiteralsExt(weight: Int) extends Extractor {
  override def extract(client: FeatureExtractor, uast: Node): Future[FeaturesReply] = {
    val req = LiteralsRequest(
      uast = Some(uast),
      options = Some(LiteralsOptions(docfreqThreshold = Threshold, weight = weight))
    )
    client.literals(req)
  }
}

case class Uast2seqExt(weight: Int, seqLen: Seq[Int], stride: Int) extends Extractor {
  override def extract(client: FeatureExtractor, uast: Node): Future[FeaturesReply] = {
    val req = Uast2seqRequest(
      uast = Some(uast),
      options = Some(Uast2seqOptions(docfreqThreshold = Threshold, weight = weight, stride = stride, seqLen = seqLen))
    )
    client.uast2Seq(req)
  }
}

case class BatchExt(identifiers: Option[IdentifiersOptions],
                    literals: Option[LiteralsOptions],
                    uast2Seq: Option[Uast2seqOptions],
                    graphlet: Option[GraphletOptions]) extends Extractor {
  override def extract(client: FeatureExtractor, uast: Node): Future[FeaturesReply] = {
    val req = ExtractRequest(
      uast = Some(uast),
      identifiers = identifiers,
      literals = literals,
      uast2Seq = uast2Seq,
      graphlet = graphlet
    )
    client.extract(req)
  }
}

object FEClient {

  val defaultThreshold = 5

  val fileLevelExtractors = Seq(
    BatchExt(
      identifiers = Some(IdentifiersOptions(docfreqThreshold = defaultThreshold, weight = 194, splitStem = true)),
      graphlet = Some(GraphletOptions(docfreqThreshold = defaultThreshold, weight = 548)),
      uast2Seq = None,
      literals = Some(LiteralsOptions(docfreqThreshold = defaultThreshold, weight = 264))
    )
  )
  val funcLevelExtractors = Seq(
    BatchExt(
      identifiers = Some(IdentifiersOptions(docfreqThreshold = defaultThreshold, weight = 535, splitStem = true)),
      graphlet = Some(GraphletOptions(docfreqThreshold = defaultThreshold, weight = 5707)),
      uast2Seq = Some(Uast2seqOptions(docfreqThreshold = defaultThreshold, weight = 369, seqLen = Seq(3), stride = 1)),
      literals = None
    )
  )

  def extract(
    uast: Node,
    client: FeatureExtractor,
    configuredFeatureExtractors: Seq[Extractor],
    log: Slf4jLogger,
    skippedFiles: Option[MapAccumulator] = None
  ): Iterable[Feature] = {

    val features = Future
      .sequence(configuredFeatureExtractors.map { fe =>
        fe.extractFeatures(client, uast)
      })

    try {
      Await.result(features, Duration(30, SECONDS)).flatten
    } catch {
      case NonFatal(e) => {
        log.error(s"feature extractor error: ${e.toString}")
        skippedFiles.foreach(_.add(e.getClass.getSimpleName -> 1))
        Iterable[Feature]()
      }
    }
  }
}

object SparkFEClient extends Logging {

  case class Config(host: String, port: Int)

  /** Key used for the option to specify the host of the feature extractor grpc service. */
  val hostKey = "spark.tech.sourced.featurext.grpc.host"

  /** Key used for the option to specify the port of the feature extractor grpc service. */
  val portKey = "spark.tech.sourced.featurext.grpc.port"

  /** Default service host. */
  val defaultHost = "127.0.0.1"

  /** Default service port. */
  val defaultPort = 9001

  private var config: Config = _
  private var client: FeatureExtractor = _

  /**
    * Returns the configuration for feature extractor.
    *
    * @param session Spark session
    * @return featurext configuration
    */
  def getConfig(session: SparkSession): Config = {
    if (config == null) { //TODO(bzz) broadcast Config beforehand and just read it here
      val host = session.conf.get(hostKey, SparkFEClient.defaultHost)
      val port = session.conf.get(portKey, SparkFEClient.defaultPort.toString).toInt
      config = Config(host, port)
    }

    config
  }

  private def getClient(config: Config): FeatureExtractor = synchronized {
    if (client == null) {
      val channel = ManagedChannelBuilder.forAddress(config.host, config.port).usePlaintext(true).build()
      client = FeatureExtractorGrpc.stub(channel)
    }

    client
  }

  def extract(
    uast: Node, config: Config,
    configuredFeatureExtractors: Seq[Extractor],
    skippedFiles: Option[MapAccumulator] = None
  ): Iterable[Feature] = {
    val client = getClient(config)
    FEClient.extract(uast, client, configuredFeatureExtractors, log, skippedFiles)
  }

}
