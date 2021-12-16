/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.kyuubi.engine.flink.operation

import java.io.IOException

import scala.collection.JavaConverters.collectionAsScalaIterableConverter

import org.apache.hive.service.rpc.thrift.{TRowSet, TTableSchema}

import org.apache.kyuubi.{KyuubiSQLException, Utils}
import org.apache.kyuubi.engine.flink.context.SessionContext
import org.apache.kyuubi.engine.flink.result.ResultSet
import org.apache.kyuubi.engine.flink.schema.RowSet
import org.apache.kyuubi.engine.flink.session.FlinkSessionImpl
import org.apache.kyuubi.operation.{AbstractOperation, OperationState}
import org.apache.kyuubi.operation.FetchOrientation.{FETCH_FIRST, FETCH_NEXT, FETCH_PRIOR, FetchOrientation}
import org.apache.kyuubi.operation.OperationState.OperationState
import org.apache.kyuubi.operation.OperationType.OperationType
import org.apache.kyuubi.operation.log.OperationLog
import org.apache.kyuubi.session.Session

abstract class FlinkOperation(
    opType: OperationType,
    session: Session)
  extends AbstractOperation(opType, session) {

  protected val sessionContext: SessionContext =
    session.asInstanceOf[FlinkSessionImpl].getSessionContext
  protected var resultSet: ResultSet = _

  override protected def beforeRun(): Unit = {
    setHasResultSet(true)
    setState(OperationState.RUNNING)
  }

  override protected def afterRun(): Unit = {
    state.synchronized {
      if (!isTerminalState(state)) {
        setState(OperationState.FINISHED)
      }
    }
    OperationLog.removeCurrentOperationLog()
  }

  override def cancel(): Unit = {
    cleanup(OperationState.CANCELED)
  }

  override def close(): Unit = {
    cleanup(OperationState.CLOSED)
    try {
      getOperationLog.foreach(_.close())
    } catch {
      case e: IOException =>
        error(e.getMessage, e)
    }
  }

  override def getResultSetSchema: TTableSchema = {
    val tTableSchema = new TTableSchema()
    resultSet.getColumns.asScala.zipWithIndex.foreach { case (f, i) =>
      tTableSchema.addToColumns(RowSet.toTColumnDesc(f, i))
    }
    tTableSchema
  }

  override def getNextRowSet(order: FetchOrientation, rowSetSize: Int): TRowSet = {
    validateDefaultFetchOrientation(order)
    assertState(OperationState.FINISHED)
    setHasResultSet(true)
    order match {
      case FETCH_NEXT => resultSet.getData.fetchNext()
      case FETCH_PRIOR => resultSet.getData.fetchPrior(rowSetSize);
      case FETCH_FIRST => resultSet.getData.fetchAbsolute(0);
    }
    val token = resultSet.getData.take(rowSetSize)
    val resultRowSet = RowSet.resultSetToTRowSet(
      token.toList,
      resultSet,
      getProtocolVersion)
    resultRowSet.setStartRowOffset(resultSet.getData.getPosition)
    resultRowSet
  }

  override def shouldRunAsync: Boolean = false

  protected def cleanup(targetState: OperationState): Unit = state.synchronized {
    if (!isTerminalState(state)) {
      setState(targetState)
      if (shouldRunAsync) {
        Option(getBackgroundHandle).foreach(_.cancel(true))
      }
    }
  }

  protected def onError(cancel: Boolean = false): PartialFunction[Throwable, Unit] = {
    // We should use Throwable instead of Exception since `java.lang.NoClassDefFoundError`
    // could be thrown.
    case e: Throwable =>
      state.synchronized {
        val errMsg = Utils.stringifyException(e)
        if (state == OperationState.TIMEOUT) {
          val ke = KyuubiSQLException(s"Timeout operating $opType: $errMsg")
          setOperationException(ke)
          throw ke
        } else if (isTerminalState(state)) {
          setOperationException(KyuubiSQLException(errMsg))
          warn(s"Ignore exception in terminal state with $statementId: $errMsg")
        } else {
          setState(OperationState.ERROR)
          error(s"Error operating $opType: $errMsg", e)
          val ke = KyuubiSQLException(s"Error operating $opType: $errMsg", e)
          setOperationException(ke)
          throw ke
        }
      }
  }

}
