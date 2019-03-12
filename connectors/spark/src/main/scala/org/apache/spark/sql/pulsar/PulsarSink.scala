/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.spark.sql.pulsar

import java.{util => ju}

import org.apache.spark.internal.Logging
import org.apache.spark.sql.{DataFrame, SQLContext}
import org.apache.spark.sql.execution.streaming.Sink

private[pulsar] class PulsarSink(
    sqlContext: SQLContext,
    executorPulsarParams: ju.Map[String, Object],
    serviceUrl: String,
    topic: String) extends Sink with Logging {

  @volatile private var latestBatchId = -1L

  override def toString: String = "PulsarSink"

  override def addBatch(batchId: Long, data: DataFrame): Unit = {
    if (batchId <= latestBatchId) {
      logInfo(s"Skipping already committed batch $batchId")
    } else {
      PulsarWriter.write(
        sqlContext.sparkSession,
        data.queryExecution,
        executorPulsarParams,
        serviceUrl,
        topic
      )
      latestBatchId = batchId
    }
  }
}