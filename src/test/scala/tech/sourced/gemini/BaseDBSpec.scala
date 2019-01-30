package tech.sourced.gemini

import com.datastax.driver.core.{Cluster, Session}
import org.scalatest.{BeforeAndAfterAll, Suite}
import tech.sourced.gemini.util.Logger
import scala.collection.JavaConverters._

case class HashtableItem(hashtable: Int, v: String, sha1: String)

trait BaseDBSpec extends BeforeAndAfterAll {
  this: Suite =>

  private val _logger = Logger("gemini")
  var keyspace : String = _
  var cassandra: Session = _

  override def beforeAll(): Unit = {
    super.beforeAll()

    val cluster = Cluster.builder()
      .addContactPoint(Gemini.defaultCassandraHost)
      .withPort(Gemini.defaultCassandraPort)
      .build()

    cassandra = cluster.connect()

    val gemini = Gemini(null, _logger, keyspace)
    gemini.dropSchema(cassandra)
    gemini.applySchema(cassandra)
  }

  def insertMeta(items: Iterable[RepoFile]): Unit = {
    val cols = Gemini.tables.metaCols
    items.foreach { case RepoFile(repo, commit, path, sha) =>
      val cql = s"""INSERT INTO $keyspace.${Gemini.tables.meta}
        (${cols.repo}, ${cols.commit}, ${cols.path}, ${cols.sha})
        VALUES ('$repo', '$commit', '$path', '$sha')"""
      cassandra.execute(cql)
    }
  }

  def insertHashtables(items: Iterable[HashtableItem], mode: String): Unit = {
    val cols = Gemini.tables.hashtablesCols
    items.foreach { case HashtableItem(ht, v, sha1) =>
      val cql = s"""INSERT INTO $keyspace.${Gemini.tables.hashtables(mode)}
        (${cols.hashtable}, ${cols.value}, ${cols.sha})
        VALUES ($ht, $v, '$sha1')"""
      cassandra.execute(cql)
    }
  }

  def insertDocFreq(docFreq: OrderedDocFreq, mode: String): Unit = {
    val docsCols = Gemini.tables.featuresDocsCols
    cassandra.execute(
      s"INSERT INTO $keyspace.${Gemini.tables.featuresDocs} (${docsCols.id}, ${docsCols.docs}) VALUES (?, ?)",
      mode, int2Integer(docFreq.docs)
    )

    val freqCols = Gemini.tables.featuresFreqCols
    docFreq.df.foreach { case(feature, weight) =>
      cassandra.execute(
        s"INSERT INTO $keyspace.${Gemini.tables.featuresFreq}" +
          s"(${freqCols.id}, ${freqCols.feature}, ${freqCols.weight}) VALUES (?, ?, ?)",
        mode, feature, int2Integer(weight)
      )
    }
  }

  override def afterAll(): Unit = {
    Gemini(null, _logger, keyspace).dropSchema(cassandra)
    cassandra.close()
    super.afterAll()
  }
}
