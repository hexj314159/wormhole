/*-
 * <<
 * wormhole
 * ==
 * Copyright (C) 2016 - 2017 EDP
 * ==
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * >>
 */


package edp.wormhole.batchjob.transform

import java.sql.Timestamp

import com.alibaba.fastjson.JSON
import edp.wormhole.common.util.DateUtils
import edp.wormhole.spark.log.EdpLogging
import edp.wormhole.ums.{UmsNamespace, UmsOpType, UmsSysField}
import org.apache.spark.sql.{DataFrame, SparkSession}

class Snapshot extends CustomClassInterface with EdpLogging{
  override def transform(session: SparkSession, inputDf: DataFrame, sourceNamespace: String, startTime:String, endTime:String, specialConfig: Option[String]): DataFrame = {
    val tableName = UmsNamespace(sourceNamespace).table
    val fromTs = if (startTime == null) DateUtils.dt2timestamp(DateUtils.yyyyMMddHHmmss(DateUtils.unixEpochTimestamp)) else DateUtils.dt2timestamp(startTime)
    val toTs = if (endTime == null) DateUtils.dt2timestamp(DateUtils.currentDateTime) else DateUtils.dt2timestamp(DateUtils.dt2dateTime(endTime))
    val specialConfigStr = new String(new sun.misc.BASE64Decoder().decodeBuffer(specialConfig.get.toString))
    val specialConfigObject = JSON.parseObject(specialConfigStr)
    val keys = specialConfigObject.getString("table_keys").trim
    inputDf.createOrReplaceTempView(tableName)
    val resultDf = session.sql(getSnapshotSqlByTs(keys,fromTs,toTs,tableName))
    session.sqlContext.dropTempTable(tableName)
    resultDf
  }

  def getSnapshotSqlByTs(keys: String, fromTs: Timestamp, toTs: Timestamp,tableName: String): String = {
    s"""
       |select * from
       |    (select *, row_number() over
       |      (partition by $keys order by ${UmsSysField.ID.toString} desc) as rn
       |    from ${tableName}
       |      where ${UmsSysField.TS.toString} >= '$fromTs' and ${UmsSysField.TS.toString} <= '$toTs')
       |    increment_filter
       |  where ${UmsSysField.OP.toString} != '${UmsOpType.DELETE.toString.toLowerCase}' and ${UmsSysField.OP.toString} != '${UmsOpType.DELETE.toString.toUpperCase()}' and rn = 1
          """.stripMargin.replace("\n", " ")
  }
}