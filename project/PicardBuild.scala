/*
 * Copyright 2012 Taro L. Saito
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import sbt._
import Keys._
import sbtrelease.ReleasePlugin._
import scala.Some
import sbt.ExclusionRule
import xerial.sbt.Pack._

object PicardBuild extends Build {

  val SCALA_VERSION = "2.10.0"

  def releaseResolver(v: String): Option[Resolver] = {
      val nexus = "https://oss.sonatype.org/"
      if (v.trim.endsWith("SNAPSHOT"))
        Some("snapshots" at nexus + "content/repositories/snapshots")
      else
        Some("releases" at nexus + "service/local/staging/deploy/maven2")
  }

  lazy val defaultJavacOptions = Seq("-encoding", "UTF-8", "-source", "1.6")

  lazy val buildSettings = Defaults.defaultSettings ++ releaseSettings ++ Seq[Setting[_]](
    organization := "org.utgenome.thirdparty",
    organizationName := "utgenome.org",
    organizationHomepage := Some(new URL("http://utgenome.org/")),
    description := "Picard: Utilities for manipulating SAM/BAM files",
    scalaVersion := SCALA_VERSION,
    publishMavenStyle := true,
    publishArtifact in Test := false,
    publishTo <<= version { v => releaseResolver(v) },
    pomIncludeRepository := {
      _ => false
    },
    parallelExecution := true,
    parallelExecution in Test := false,
    autoScalaLibrary := false,
    crossPaths := false,
    javacOptions in Compile := defaultJavacOptions ++ Seq("-target", "1.6", "-proc:none"),
    javacOptions in Compile in doc := defaultJavacOptions ++ Seq("-windowtitle", "Picard API", "-linkoffline", "http://docs.oracle.com/javase/6/docs/api/", "http://docs.oracle.com/javase/6/docs/api/"),
    pomExtra := {
      <url>http://utgenome.org/</url>
        <licenses>
          <license>
            <name>Apache 2</name>
            <url>http://www.apache.org/licenses/LICENSE-2.0.txt</url>
          </license>
        </licenses>
        <scm>
          <connection>scm:git:github.com/utgenome/picard.git</connection>
          <developerConnection>scm:git:git@github.com:utgenome/picard.git</developerConnection>
          <url>github.com/utgenome/picard.git</url>
        </scm>
        <properties>
          <scala.version>
            {SCALA_VERSION}
          </scala.version>
          <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        </properties>
        <developers>
          <developer>
            <id>leo</id>
            <name>Taro L. Saito</name>
            <url>http://xerial.org/leo</url>
          </developer>
        </developers>
    }
  )

  import Dependencies._


  private val dependentScope = "test->test;compile->compile"

  private def srcFilter(path:String) = new sbt.FileFilter {
    def accept(f:File) : Boolean = {
      f.getName.endsWith(".jar") || (
        f.getPath.contains(path) &&
          f.getName.endsWith(".java") && !f.getName.endsWith("MetricsDoclet.java")
        )
    }
  }

  lazy val root = Project(
    id = "picard",
    base = file("."),
    settings = buildSettings ++ packSettings ++ Seq(
      description := "Picard",
      packMain := Map("picard" -> "utgenome.picard.PicardMain"),
      description := "Picard library",
      javaSource in Compile <<= baseDirectory(_ / "src/java"),
      javaSource in Test <<= baseDirectory(_ / "src/tests/java"),
      //includeFilter in Compile := srcFilter("net/sf"),
      //includeFilter in Test  := srcFilter("net/sf"),
      libraryDependencies ++= testLib ++ mainLib
    )
  )


  object Dependencies {

    val mainLib = Seq(
      "org.apache.ant" % "ant" % "1.8.2",
      "org.xerial.snappy" % "snappy-java" % "1.0.5-M3"
    )

    val testLib = Seq(
      "junit" % "junit" % "4.10" % "provided",
      "org.testng" % "testng" % "5.5" % "provided"
    )


  }

}








