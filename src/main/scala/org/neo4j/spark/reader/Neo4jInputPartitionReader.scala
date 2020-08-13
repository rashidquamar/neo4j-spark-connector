package org.neo4j.spark.reader

import org.apache.spark.internal.Logging
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.sources.Filter
import org.apache.spark.sql.sources.v2.reader.{InputPartition, InputPartitionReader}
import org.apache.spark.sql.types.StructType
import org.neo4j.driver.{Record, Session, Transaction}
import org.neo4j.spark.service.{MappingService, Neo4jQueryReadStrategy, Neo4jQueryService, Neo4jReadMappingStrategy}
import org.neo4j.spark.util.Neo4jUtil
import org.neo4j.spark.{DriverCache, Neo4jOptions}

import scala.collection.JavaConverters._

class Neo4jInputPartitionReader(private val options: Neo4jOptions,
                                private val filters: Array[Filter],
                                private val schema: StructType,
                                private val jobId: String,
                                private val partitionId: Int = 0,
                                private val skip: Long = -1L,
                                private val limit: Long = -1L) extends InputPartition[InternalRow]
  with InputPartitionReader[InternalRow]
  with Logging {

  private var result: Iterator[Record] = _
  private var session: Session = _
  private var transaction: Transaction = _
  private val driverCache: DriverCache = new DriverCache(options.connection,
    if (partitionId > 0) s"$jobId-$partitionId" else jobId)

  private val query: String = new Neo4jQueryService(options, new Neo4jQueryReadStrategy(filters))
    .createQuery()
    .concat(if (skip != -1 && limit != -1) s" SKIP $$skip LIMIT $$limit" else "")

  private val mappingService = new MappingService(new Neo4jReadMappingStrategy(options), options)

  override def createPartitionReader(): InputPartitionReader[InternalRow] = new Neo4jInputPartitionReader(options, filters, schema,
    jobId, partitionId, skip, limit)

  def next: Boolean = {
    if (result == null) {
      session = driverCache.getOrCreate().session(options.session.toNeo4jSession)
      transaction = session.beginTransaction()
      log.info(s"Running the following query on Neo4j: $query")
      val skipLimitParams: java.util.Map[String, AnyRef] = Map[String, AnyRef](
        "skip" -> skip.asInstanceOf[AnyRef], "limit" -> limit.asInstanceOf[AnyRef])
        .asJava
      result = transaction.run(query, skipLimitParams).asScala
    }

    result.hasNext
  }

  def get: InternalRow = mappingService.convert(result.next(), schema)

  def close(): Unit = {
    Neo4jUtil.closeSafety(transaction, log)
    Neo4jUtil.closeSafety(session, log)
    driverCache.close()
  }

}