package tech.sourced.gemini

import com.datastax.driver.core.Session
import com.datastax.spark.connector.cql.CassandraConnector
import io.grpc.{ManagedChannelBuilder, ServerBuilder}
import java.io.File
import java.nio.file.Files

import gopkg.in.bblfsh.sdk.v1.protocol.generated._
import gopkg.in.bblfsh.sdk.v1.protocol.generated.ProtocolServiceGrpc.ProtocolService
import gopkg.in.bblfsh.sdk.v1.uast.generated.Node
import org.apache.spark.SparkConf
import org.apache.spark.internal.Logging
import org.apache.spark.sql.functions._
import org.bblfsh.client.BblfshClient
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers, Tag}
import tech.sourced.featurext.generated.service._
import tech.sourced.featurext.generated.service.FeatureExtractorGrpc.FeatureExtractor

import scala.concurrent.{ExecutionContext, Future}
import scala.util.parsing.json.JSON

class CassandraSparkSpec extends FlatSpec
  with Matchers
  with BaseSparkSpec
  with Logging
  with BeforeAndAfterAll {

  //to start Embedded Cassandra:
  // with SparkTemplate with EmbeddedCassandra
  // useCassandraConfig(Seq(YamlTransformations.Default))
  // override def clearCache(): Unit = CassandraConnector.evictCache()
  // + spark-cassandra-connector/blob/master/spark-cassandra-connector/src/it/resources/cassandra-3.2.yaml.template

  var session: Session = _
  var bblfshClient: BblfshClient = _
  var feClient: FeatureExtractor = _

  val defaultConf: SparkConf = new SparkConf(true)
    .set("spark.cassandra.connection.host", Gemini.defaultCassandraHost)
    .set("spark.cassandra.connection.port", Gemini.defaultCassandraPort.toString)
    .set("spark.cassandra.connection.keep_alive_ms", "5000")
    .set("spark.cassandra.connection.timeout_ms", "30000")
    .set("spark.ui.showConsoleProgress", "false")
    .set("spark.ui.enabled", "false")
    .set("spark.cleaner.ttl", "3600")

  useSparkConf(defaultConf)

  val logger = Logger("gemini")

  override def beforeAll(): Unit = {
    super.beforeAll()
    session = CassandraConnector(defaultConf).openSession()
    prepareKeyspace("src/test/resources/siva/unique-files", UNIQUES)
    prepareKeyspace("src/test/resources/siva/duplicate-files", DUPLICATES)
    prepareSimilaritiesKeyspace(SIMILARITIES)
    bblfshClient = BblfshClient.apply(Gemini.defaultBblfshHost, Gemini.defaultBblfshPort)
    val channel = ManagedChannelBuilder
      .forAddress(Gemini.defaultFeHost, Gemini.defaultFePort)
      .usePlaintext(true)
      .build()
    feClient = FeatureExtractorGrpc.stub(channel)
  }

  override def afterAll(): Unit = {
    Gemini(null, logger, UNIQUES).dropSchema(session)
    Gemini(null, logger, DUPLICATES).dropSchema(session)
    Gemini(null, logger, SIMILARITIES).dropSchema(session)
    super.afterAll()
    session.close()
  }

  val expectedDuplicateFiles = List(
    "model_test.go",
    "MAINTAINERS",
    "changes.go",
    "model.go",
    "file.py",
    "cli/borges/version.go",
    "Makefile",
    "doc.go"
  )

  def prepareKeyspace(sivaPath: String, keyspace: String): Unit = {
    val gemini = Gemini(sparkSession, logger, keyspace)
    gemini.dropSchema(session)
    gemini.applySchema(session)
    println("Hash")
    gemini.hashAndSave(sivaPath)
    println("Done")
  }

  case class hashtableItem(hashtable: Int, v: String, sha1: String)

  def readHashItemsFromFile(path: String): Iterable[hashtableItem] = {
    val file = new File(path)
    val byteArray = Files.readAllBytes(file.toPath)
    val jsonData = JSON.parseFull(new String(byteArray))

    jsonData match {
      case Some(m: List[_]) => {
        m.map { l =>
          val r = l.asInstanceOf[List[String]]
          hashtableItem(hashtable=r(0).toInt, v=r(1), sha1=r(2))
        }
      }
      case _ => throw new Exception("incorrect json")
    }
  }

  def prepareSimilaritiesKeyspace(keyspace: String): Unit = {
    val gemini = Gemini(sparkSession, logger, keyspace)
    gemini.dropSchema(session)
    gemini.applySchema(session)

    val items = readHashItemsFromFile("src/test/resources/hashtables.json")
    items.foreach { case hashtableItem(ht, v, sha1) =>
      val cql = s"INSERT INTO $keyspace.hashtables (hashtable, value, sha1) VALUES ($ht, $v, '$sha1')"
      session.execute(cql)
    }
  }

  val UNIQUES = "test_hashes_uniques"
  val DUPLICATES = "test_hashes_duplicates"
  val SIMILARITIES = "test_hashes_similarities"

  object Cassandra extends Tag("Cassandra")

  // such tests require bblfsh & feature extractors
  object Integration extends Tag("Integration")

  "Read from Database" should "return same results as written" in {
    val gemini = Gemini(sparkSession, logger, UNIQUES)

    println("Query")
    val (duplicates, _) = gemini.query("src/test/resources/LICENSE", session, bblfshClient, feClient)
    println("Done")

    duplicates should not be empty
    duplicates.head.sha should be("097f4a292c384e002c5b5ce8e15d746849af7b37") // git hash-object -w LICENSE
    duplicates.head.repo should be("null/Users/alex/src-d/gemini")
    duplicates.head.commit should be("4aa29ac236c55ebbfbef149fef7054d25832717f")
  }

  "Query for duplicates in single repository" should "return 2 files" in {
    val gemini = Gemini(sparkSession, logger, DUPLICATES)

    // 2 file in 9279be3cf07fb3cca4fc964b27acea57e0af461b.siva
    val sha1 = Gemini.findDuplicatesOfBlobHash("c4e5bcc8001f80acc238877174130845c5c39aa3", session, DUPLICATES)

    sha1 should not be empty
    sha1.size shouldEqual 2
  }

  /**
    * - it uses mocks for FE and Bblfsh
    * - it loads .json fixtures to DB for two hashed files, one of which is src/test/resources/consumer.go
    * - feature extractor mock always return features, extracted for this file
    * - docfreq.json and params.json generated by apollo with default params
    * for siva files in src/test/resources/duplicate-files
    * - it checks that 1 similar file is returns
    *
    * DB fixture created manually by getting values from DB generated by apollo
    * to generate new fixture for features extractors - just dump response of service to json
    * there is no fixture for bblfsh because we do nothing with UAST beside passing it to feature extractor
    */
  "Query for similar files" should "return 2 file (duplicated & similar)" in {
    val features = readFeaturesFromFile("src/test/resources/features.json")

    val server = ServerBuilder
      .forPort(0)
      .addService(ProtocolServiceGrpc.bindService(bblfshMock(new Node), ExecutionContext.global))
      .addService(FeatureExtractorGrpc.bindService(feMock(features), ExecutionContext.global))
      .build
      .start()

    val gemini = Gemini(sparkSession, logger, SIMILARITIES)
    val channel = ManagedChannelBuilder.forAddress("localhost", server.getPort).usePlaintext(true).build()
    val bblfshStub = BblfshClient("localhost", server.getPort)
    val feStub = FeatureExtractorGrpc.stub(channel)

    // full duplicate
    val dupFile = new File("src/test/resources/consumer.go")
    var similar =
      gemini.findSimilarForFile(
        dupFile,
        session,
        bblfshStub,
        feStub,
        "src/test/resources/docfreq.json",
        "src/test/resources/params.json",
        9,
        13)

    similar.size shouldEqual 2
    similar(0) shouldEqual "9f653118e787febce824759bb5c4ef17fe4da7b0"
    similar(1) shouldEqual "e32d54ae4b969ac13f737efaf1c11ccfc52bbe5b"

    server.shutdown()
  }

  "Report from Cassandra using GROUP BY" should "return duplicate files" taggedAs Cassandra in {
    val gemini = Gemini(sparkSession, logger, DUPLICATES)

    println("Query")
    val report = gemini.reportCassandraCondensed(session)
    println("Done")

    report should have size expectedDuplicateFiles.size
    report foreach (_.count should be(2))
  }

  "Detailed Report from Cassandra using GROUP BY" should "return duplicate files" taggedAs Cassandra in {
    val gemini = Gemini(sparkSession, logger, DUPLICATES)

    println("Query")
    val detailedReport = gemini.reportCassandraGroupBy(session)
    println("Done")

    val duplicatedFileNames = detailedReport map (_.head.path)
    duplicatedFileNames.toSeq should contain theSameElementsAs expectedDuplicateFiles
  }

  "Detailed Report from Database" should "return duplicate files" in {
    val gemini = Gemini(sparkSession, logger, DUPLICATES)

    println("Query")
    val detailedReport = gemini.report(session)
    println("Done")

    val duplicatedFileNames = detailedReport map (_.head.path)
    duplicatedFileNames.toSeq should contain theSameElementsAs expectedDuplicateFiles
  }

  "Report from Database with unique files" should "return no duplicate files" in {
    val gemini = Gemini(sparkSession, logger, UNIQUES)

    println("Query")
    val report = gemini.report(session)
    println("Done")

    report should have size 0
  }

  "Hash with limit" should "collect files only from limit repos" in {
    val gemini = Gemini(sparkSession)
    val repos = gemini.hash("src/test/resources/siva", 1).select("repository_id").distinct().count()
    repos should be(1)
  }

  "Hash extract uast" should "return correct values" taggedAs Integration in {
    val filePaths = Array(
      // should be ignored
      ".gitignore",
      // should be processed
      "archiver.go",
      "archiver_test.go"
    )

    val gemini = Gemini(sparkSession)
    val files = gemini.hash("src/test/resources/siva/duplicate-files")
      .filter(col("repository_id") === "github.com/erizocosmico/borges.git")
      .filter(col("path").isin(filePaths: _*))
    val uasts = gemini.sparkExtractUast(files).collect()

    uasts should have size 2
    uasts.map(_.getString(0)) shouldEqual List(
      "github.com/erizocosmico/borges.git//archiver.go@6b600b3f0a6172af59eddecef8ea39fde80fe66c",
      "github.com/erizocosmico/borges.git//archiver_test.go@4cfd4914583cf645a46d78eb4e7d0363fb95391f"
    )
  }

  "Hash extract features" should "return rdd" taggedAs Integration in {
    val filePaths = Array(
      "archiver.go",
      "archiver_test.go"
    )
    val gemini = Gemini(sparkSession)
    val files = gemini.hash("src/test/resources/siva/duplicate-files")
      .filter(col("repository_id") === "github.com/erizocosmico/borges.git")
      .filter(col("path").isin(filePaths: _*))
    val uasts = gemini.sparkExtractUast(files)
    val features = gemini.sparkFeatures(uasts).collect()

    features.size should be > 1
    // check one feature to make sure data is written in correct column
    features(0).key(0) should startWith ("i.")
    features(0).key(1) should include ("github.com")
    features(0).weight.toInt should be > 1
  }

  "Hash makeDocFreq" should "generate docFreq object" taggedAs Integration in {
    val filePaths = Array(
      "archiver.go",
      "archiver_test.go"
    )
    val gemini = Gemini(sparkSession)
    val files = gemini.hash("src/test/resources/siva/duplicate-files")
      .filter(col("repository_id") === "github.com/erizocosmico/borges.git")
      .filter(col("path").isin(filePaths: _*))
    val uasts = gemini.sparkExtractUast(files)
    val features = gemini.sparkFeatures(uasts)
    val docFreq = gemini.makeDocFreq(uasts, features)

    // check that object isn't empty
    docFreq.docs should be > 1
    docFreq.tokens.size should be > 1
    docFreq.df.keys.size should be > 1
  }

  def readFeaturesFromFile(path: String): Seq[Feature] = {
    val file = new File(path)
    val byteArray = Files.readAllBytes(file.toPath)
    val jsonData = JSON.parseFull(new String(byteArray))

    jsonData match {
      case Some(m: Map[_, _]) => {
        val featuresMap = m.asInstanceOf[Map[String, Double]]
        val iter = featuresMap.map { case(key, value) =>
          Feature(name = key, weight = value.toInt)
        }
        iter.toSeq
      }
      case _ => throw new Exception("incorrect json")
    }
  }

  def bblfshMock(uast: Node): ProtocolService = {
    class BblfshServerMock extends ProtocolService {
      override def parse(request: ParseRequest): Future[ParseResponse] = {
        Future.successful(ParseResponse(uast = Some(uast)))
      }

      override def nativeParse(request: NativeParseRequest): Future[NativeParseResponse] = {
        Future.successful(NativeParseResponse())
      }

      override def version(request: VersionRequest): Future[VersionResponse] = {
        Future.successful(VersionResponse())
      }
    }

    new BblfshServerMock
  }

  def feMock(features: Seq[Feature]): FeatureExtractor = {
    class FEServerMock extends FeatureExtractor {
      override def identifiers(request: IdentifiersRequest): Future[FeaturesReply] = {
        Future.successful(FeaturesReply(features=features))
      }

      override def literals(request: LiteralsRequest): Future[FeaturesReply] = {
        Future.successful(FeaturesReply())
      }

      override def uast2Seq(request: Uast2seqRequest): Future[FeaturesReply] = {
        Future.successful(FeaturesReply())
      }

      override def graphlet(request: GraphletRequest): Future[FeaturesReply] = {
        Future.successful(FeaturesReply())
      }
    }

    new FEServerMock
  }

  //TODO(bzz): add test \w repo URL list, that will be fetched by Engine
}
