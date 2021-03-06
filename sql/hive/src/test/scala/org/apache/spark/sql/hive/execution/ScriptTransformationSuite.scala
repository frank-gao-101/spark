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

package org.apache.spark.sql.hive.execution

import java.sql.Timestamp

import org.apache.hadoop.hive.serde2.`lazy`.LazySimpleSerDe
import org.scalatest.Assertions._
import org.scalatest.BeforeAndAfterEach
import org.scalatest.exceptions.TestFailedException

import org.apache.spark.{SparkException, TaskContext, TestUtils}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.Column
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.{Attribute, AttributeReference}
import org.apache.spark.sql.catalyst.plans.physical.Partitioning
import org.apache.spark.sql.execution.{SparkPlan, SparkPlanTest, UnaryExecNode}
import org.apache.spark.sql.hive.HiveUtils
import org.apache.spark.sql.hive.test.TestHiveSingleton
import org.apache.spark.sql.test.SQLTestUtils
import org.apache.spark.sql.types.StringType

class ScriptTransformationSuite extends SparkPlanTest with SQLTestUtils with TestHiveSingleton
  with BeforeAndAfterEach {
  import spark.implicits._

  private val noSerdeIOSchema = HiveScriptIOSchema(
    inputRowFormat = Seq.empty,
    outputRowFormat = Seq.empty,
    inputSerdeClass = None,
    outputSerdeClass = None,
    inputSerdeProps = Seq.empty,
    outputSerdeProps = Seq.empty,
    recordReaderClass = None,
    recordWriterClass = None,
    schemaLess = false
  )

  private val serdeIOSchema = noSerdeIOSchema.copy(
    inputSerdeClass = Some(classOf[LazySimpleSerDe].getCanonicalName),
    outputSerdeClass = Some(classOf[LazySimpleSerDe].getCanonicalName)
  )

  private var defaultUncaughtExceptionHandler: Thread.UncaughtExceptionHandler = _

  private val uncaughtExceptionHandler = new TestUncaughtExceptionHandler

  protected override def beforeAll(): Unit = {
    super.beforeAll()
    defaultUncaughtExceptionHandler = Thread.getDefaultUncaughtExceptionHandler
    Thread.setDefaultUncaughtExceptionHandler(uncaughtExceptionHandler)
  }

  protected override def afterAll(): Unit = {
    super.afterAll()
    Thread.setDefaultUncaughtExceptionHandler(defaultUncaughtExceptionHandler)
  }

  override protected def afterEach(): Unit = {
    super.afterEach()
    uncaughtExceptionHandler.cleanStatus()
  }

  test("cat without SerDe") {
    assume(TestUtils.testCommandAvailable("/bin/bash"))

    val rowsDf = Seq("a", "b", "c").map(Tuple1.apply).toDF("a")
    checkAnswer(
      rowsDf,
      (child: SparkPlan) => new ScriptTransformationExec(
        input = Seq(rowsDf.col("a").expr),
        script = "cat",
        output = Seq(AttributeReference("a", StringType)()),
        child = child,
        ioschema = noSerdeIOSchema
      ),
      rowsDf.collect())
    assert(uncaughtExceptionHandler.exception.isEmpty)
  }

  test("cat with LazySimpleSerDe") {
    assume(TestUtils.testCommandAvailable("/bin/bash"))

    val rowsDf = Seq("a", "b", "c").map(Tuple1.apply).toDF("a")
    checkAnswer(
      rowsDf,
      (child: SparkPlan) => new ScriptTransformationExec(
        input = Seq(rowsDf.col("a").expr),
        script = "cat",
        output = Seq(AttributeReference("a", StringType)()),
        child = child,
        ioschema = serdeIOSchema
      ),
      rowsDf.collect())
    assert(uncaughtExceptionHandler.exception.isEmpty)
  }

  test("script transformation should not swallow errors from upstream operators (no serde)") {
    assume(TestUtils.testCommandAvailable("/bin/bash"))

    val rowsDf = Seq("a", "b", "c").map(Tuple1.apply).toDF("a")
    val e = intercept[TestFailedException] {
      checkAnswer(
        rowsDf,
        (child: SparkPlan) => new ScriptTransformationExec(
          input = Seq(rowsDf.col("a").expr),
          script = "cat",
          output = Seq(AttributeReference("a", StringType)()),
          child = ExceptionInjectingOperator(child),
          ioschema = noSerdeIOSchema
        ),
        rowsDf.collect())
    }
    assert(e.getMessage().contains("intentional exception"))
    // Before SPARK-25158, uncaughtExceptionHandler will catch IllegalArgumentException
    assert(uncaughtExceptionHandler.exception.isEmpty)
  }

  test("script transformation should not swallow errors from upstream operators (with serde)") {
    assume(TestUtils.testCommandAvailable("/bin/bash"))

    val rowsDf = Seq("a", "b", "c").map(Tuple1.apply).toDF("a")
    val e = intercept[TestFailedException] {
      checkAnswer(
        rowsDf,
        (child: SparkPlan) => new ScriptTransformationExec(
          input = Seq(rowsDf.col("a").expr),
          script = "cat",
          output = Seq(AttributeReference("a", StringType)()),
          child = ExceptionInjectingOperator(child),
          ioschema = serdeIOSchema
        ),
        rowsDf.collect())
    }
    assert(e.getMessage().contains("intentional exception"))
    // Before SPARK-25158, uncaughtExceptionHandler will catch IllegalArgumentException
    assert(uncaughtExceptionHandler.exception.isEmpty)
  }

  test("SPARK-14400 script transformation should fail for bad script command") {
    assume(TestUtils.testCommandAvailable("/bin/bash"))

    val rowsDf = Seq("a", "b", "c").map(Tuple1.apply).toDF("a")

    val e = intercept[SparkException] {
      val plan =
        new ScriptTransformationExec(
          input = Seq(rowsDf.col("a").expr),
          script = "some_non_existent_command",
          output = Seq(AttributeReference("a", StringType)()),
          child = rowsDf.queryExecution.sparkPlan,
          ioschema = serdeIOSchema)
      SparkPlanTest.executePlan(plan, hiveContext)
    }
    assert(e.getMessage.contains("Subprocess exited with status"))
    assert(uncaughtExceptionHandler.exception.isEmpty)
  }

  test("SPARK-24339 verify the result after pruning the unused columns") {
    val rowsDf = Seq(
      ("Bob", 16, 176),
      ("Alice", 32, 164),
      ("David", 60, 192),
      ("Amy", 24, 180)).toDF("name", "age", "height")

    checkAnswer(
      rowsDf,
      (child: SparkPlan) => new ScriptTransformationExec(
        input = Seq(rowsDf.col("name").expr),
        script = "cat",
        output = Seq(AttributeReference("name", StringType)()),
        child = child,
        ioschema = serdeIOSchema
      ),
      rowsDf.select("name").collect())
    assert(uncaughtExceptionHandler.exception.isEmpty)
  }

  test("SPARK-25990: TRANSFORM should handle different data types correctly") {
    assume(TestUtils.testCommandAvailable("python"))
    val scriptFilePath = getTestResourcePath("test_script.py")

    withTempView("v") {
      val df = Seq(
        (1, "1", 1.0, BigDecimal(1.0), new Timestamp(1)),
        (2, "2", 2.0, BigDecimal(2.0), new Timestamp(2)),
        (3, "3", 3.0, BigDecimal(3.0), new Timestamp(3))
      ).toDF("a", "b", "c", "d", "e") // Note column d's data type is Decimal(38, 18)
      df.createTempView("v")

      val query = sql(
        s"""
          |SELECT
          |TRANSFORM(a, b, c, d, e)
          |USING 'python $scriptFilePath' AS (a, b, c, d, e)
          |FROM v
        """.stripMargin)

      // In Hive1.2, it does not do well on Decimal conversion. For example, in this case,
      // it converts a decimal value's type from Decimal(38, 18) to Decimal(1, 0). So we need
      // do extra cast here for Hive1.2. But in Hive2.3, it still keeps the original Decimal type.
      val decimalToString: Column => Column = if (HiveUtils.isHive23) {
        c => c.cast("string")
      } else {
        c => c.cast("decimal(1, 0)").cast("string")
      }
      checkAnswer(query, identity, df.select(
        'a.cast("string"),
        'b.cast("string"),
        'c.cast("string"),
        decimalToString('d),
        'e.cast("string")).collect())
    }
  }
}

private case class ExceptionInjectingOperator(child: SparkPlan) extends UnaryExecNode {
  override protected def doExecute(): RDD[InternalRow] = {
    child.execute().map { x =>
      assert(TaskContext.get() != null) // Make sure that TaskContext is defined.
      Thread.sleep(1000) // This sleep gives the external process time to start.
      throw new IllegalArgumentException("intentional exception")
    }
  }

  override def output: Seq[Attribute] = child.output

  override def outputPartitioning: Partitioning = child.outputPartitioning
}
