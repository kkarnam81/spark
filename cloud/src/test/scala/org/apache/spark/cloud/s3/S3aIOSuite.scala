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

package org.apache.spark.cloud.s3

import java.io.FileNotFoundException
import java.net.URI

import scala.reflect.ClassTag

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{CommonConfigurationKeysPublic, FileStatus, FileSystem, Path}
import org.apache.hadoop.fs.s3a.Constants
import org.apache.hadoop.io.{NullWritable, Text}

import org.apache.spark.SparkContext
import org.apache.spark.cloud.CloudSuite
import org.apache.spark.rdd.RDD

private[spark] class S3aIOSuite extends CloudSuite {


  val SceneListGZ = new Path("s3a://landsat-pds/scene_list.gz")
  /** number of lines, from `gunzip` + `wc -l` */
  val ExpectedSceneListLines = 447919

  override def enabled: Boolean = super.enabled && conf.getBoolean(AWS_TESTS_ENABLED, false)

  init()

  def init(): Unit = {
    // propagate S3 credentials
    if (enabled) {
      val id = requiredOption(AWS_ACCOUNT_ID)
      val secret = requiredOption(AWS_ACCOUNT_SECRET)
      conf.set("fs.s3n.awsAccessKeyId", id)
      conf.set("fs.s3n.awsSecretAccessKey", secret)
      conf.set(Constants.BUFFER_DIR, localTmpDir.getAbsolutePath)
      val s3aURI = new URI(requiredOption(S3_TEST_URI))
      logInfo(s"Executing S3 tests against $s3aURI")
      createFilesystem(s3aURI)
    }
  }

  after {
    cleanFilesystemInTeardown()
  }

  ctest("Create, delete directory") {
    val fs = filesystem.get
    val path = TestDir
    fs.mkdirs(path)
    val st = stat(path)
    logInfo(s"Created filesystem entry $path: $st")
    fs.delete(path, true)
    intercept[FileNotFoundException] {
      val st2 = stat(path)
      logError(s"Got status $st2")
    }
  }

  def stat(path: Path): FileStatus = {
    getFS(path).getFileStatus(path)
  }

  def getFS(path: Path): FileSystem = {
    FileSystem.get(path.toUri, conf)
  }

  ctest("Generate then Read data -File Output Committer") {
    sc = new SparkContext("local", "test", newSparkConf())
    val conf = sc.hadoopConfiguration
    assert(fsURI.toString === conf.get(CommonConfigurationKeysPublic.FS_DEFAULT_NAME_KEY))
    val entryCount = testEntryCount
    val numbers = sc.parallelize(1 to entryCount)
    val example1 = new Path(TestDir, "example1")
    numbers.saveAsTextFile(example1.toString)
    val st = stat(example1)
    assert(st.isDirectory, s"Not a dir: $st")
    val fs = filesystem.get
    val children = fs.listStatus(example1)
    assert(children.nonEmpty, s"No children under $example1")
    children.foreach { child =>
      logInfo(s"$child")
      assert(child.getLen > 0 || child.getPath.getName === "_SUCCESS",
        s"empty output $child")
    }
    val parts = children.flatMap { child =>
      if (child.getLen > 0) Seq(child) else Nil
    }
    assert(parts.length === 1)
    val parts0 = parts(0)
    // now read it in
    val input = sc.textFile(parts0.getPath.toString)
    val results = input.collect()
    assert(entryCount === results.length, s"size of results read in from $parts0")
  }

  ctest("New Hadoop API") {
    sc = new SparkContext("local", "test", newSparkConf())
    val conf = sc.hadoopConfiguration
    val entryCount = testEntryCount
    val numbers = sc.parallelize(1 to entryCount)
    val example1 = new Path(TestDir, "example1")
    saveAsTextFile(numbers, example1, conf)
  }


  /**
   * Save this RDD as a text file, using string representations of elements.
   *
   * There's a bit of convoluted-ness here, as this supports writing to any Hadoop FS,
   * rather than the default one in the configuration ... this is addressed by creating a
   * new configuration
   */
  def saveAsTextFile[T](rdd: RDD[T], path: Path, conf: Configuration)
  : Unit = {
    rdd.withScope {
      val nullWritableClassTag = implicitly[ClassTag[NullWritable]]
      val textClassTag = implicitly[ClassTag[Text]]
      val r = rdd.mapPartitions { iter =>
        val text = new Text()
        iter.map { x =>
          text.set(x.toString)
          (NullWritable.get(), text)
        }
      }
      val pathFS = FileSystem.get(path.toUri, conf)
      val confWithTargetFS = new Configuration(conf)
      confWithTargetFS.set(CommonConfigurationKeysPublic.FS_DEFAULT_NAME_KEY,
        pathFS.getUri.toString)
      val pairOps = RDD.rddToPairRDDFunctions(r)(nullWritableClassTag, textClassTag, null)
      pairOps.saveAsNewAPIHadoopFile(path.toUri.toString,
        pairOps.keyClass, pairOps.valueClass,
        classOf[org.apache.hadoop.mapreduce.lib.output.TextOutputFormat[NullWritable, Text]],
        confWithTargetFS)
    }
  }

  ctest("Read compressed CSV") {
    val source = SceneListGZ
    sc = new SparkContext("local", "test", newSparkConf(source))
    val sceneInfo = stat(source)
    logInfo(s"Compressed size = ${sceneInfo.getLen}")
    val input = sc.textFile(source.toString)
    val count = input.count()
    logInfo(s" size of $source = $count rows")
    assert(ExpectedSceneListLines === count, s"Number of rows in $source")
  }

  ctest("Read compressed CSV differentFS") {
    sc = new SparkContext("local", "test", newSparkConf())
    val source = SceneListGZ
    val input = sc.textFile(source.toString)
    val count = input.count()
    logInfo(s" size of $source = $count rows")
    assert(ExpectedSceneListLines === count, s"Number of rows in $source")
  }

  /**
   * Assess cost of seek and read operations.
   * When moving the cursor in an input stream, an HTTP connection may be closed and
   * then re-opened. This can be very expensive; tactics like streaming forwards instead
   * of seeking, and/or postponing movement until the following read ("lazy seek") try
   * to address this. Logging these operation times helps track performance.
   * This test also tries to catch out a regression, where a `close()` operation
   * is implemented through reading through the entire input stream. This is exhibited
   * in the time to close() while at offset 0 being O(len(file)).
   */
  ctest("Cost of seek and close") {
    sc = new SparkContext("local", "test", newSparkConf())
    val source = SceneListGZ
    val fs = getFS(source)
    val st = duration("stat") {
      fs.getFileStatus(source)
    }
    val out = duration("open") {
      fs.open(source)
    }
    duration("read[0]") {
      assert(-1 !== out.read())
    }
    duration("seek[EOF-2]") {
      out.seek(st.getLen - 2)
    }
    duration("read[EOF-2]") {
      assert(-1 !== out.read())
    }
    duration("read[1]") {
      val bytes = new Array[Byte](64)
      assert(-1 !== out.readFully(1L, bytes))
    }
    duration("seek[256]") {
      out.seek(256)
    }
    duration("read[256]") {
      assert(-1 !== out.read())
    }
    duration("close()") {
      out.close
    }


  }

  /**
   * Measure the duration of an operation, log it
   * @param operation operation description
   * @param testFun function to execute
   * @return time in milliseconds
   */
  def duration[T](operation: String)(testFun: => T): T = {
    val start = System.currentTimeMillis()
    try {
      testFun
    } finally {
      val end = System.currentTimeMillis()
      val d = end - start
      logInfo(s"Duration of $operation = $d millis")
    }
  }
}
