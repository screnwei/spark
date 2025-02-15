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

package org.apache.spark

import java.net.URL

import scala.collection.immutable.SortedMap

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.core.`type`.TypeReference
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule

import org.apache.spark.util.Utils

/**
 * Information associated with an error subclass.
 *
 * @param subClass SubClass associated with this class.
 * @param message C-style message format compatible with printf.
 *                The error message is constructed by concatenating the lines with newlines.
 */
private[spark] case class ErrorSubInfo(message: Seq[String]) {
  // For compatibility with multi-line error messages
  @JsonIgnore
  val messageFormat: String = message.mkString("\n")
}

/**
 * Information associated with an error class.
 *
 * @param sqlState SQLSTATE associated with this class.
 * @param subClass A sequence of subclasses
 * @param message C-style message format compatible with printf.
 *                The error message is constructed by concatenating the lines with newlines.
 */
private[spark] case class ErrorInfo(message: Seq[String],
                                    subClass: Option[Map[String, ErrorSubInfo]],
                                    sqlState: Option[String]) {
  // For compatibility with multi-line error messages
  @JsonIgnore
  val messageFormat: String = message.mkString("\n")
}

/**
 * Companion object used by instances of [[SparkThrowable]] to access error class information and
 * construct error messages.
 */
private[spark] object SparkThrowableHelper {
  val errorClassesUrl: URL =
    Utils.getSparkClassLoader.getResource("error/error-classes.json")
  val errorClassToInfoMap: SortedMap[String, ErrorInfo] = {
    val mapper: JsonMapper = JsonMapper.builder()
      .addModule(DefaultScalaModule)
      .build()
    mapper.readValue(errorClassesUrl, new TypeReference[SortedMap[String, ErrorInfo]]() {})
  }

  def getMessage(
      errorClass: String,
      messageParameters: Array[String],
      queryContext: String = ""): String = {
    val errorInfo = errorClassToInfoMap.getOrElse(errorClass,
      throw new IllegalArgumentException(s"Cannot find error class '$errorClass'"))
    val (displayClass, displayMessageParameters, displayFormat) = if (errorInfo.subClass.isEmpty) {
      (errorClass, messageParameters, errorInfo.messageFormat)
    } else {
      val subClass = errorInfo.subClass.get
      val subErrorClass = messageParameters.head
      val errorSubInfo = subClass.getOrElse(subErrorClass,
        throw new IllegalArgumentException(s"Cannot find sub error class '$subErrorClass'"))
      (errorClass + "." + subErrorClass, messageParameters.tail,
        errorInfo.messageFormat + errorSubInfo.messageFormat)
    }
    val displayMessage = String.format(
      displayFormat.replaceAll("<[a-zA-Z0-9_-]+>", "%s"),
      displayMessageParameters : _*)
    val displayQueryContext = if (queryContext.isEmpty) {
      ""
    } else {
      s"\n$queryContext"
    }
    s"[$displayClass] $displayMessage$displayQueryContext"
  }

  def getSqlState(errorClass: String): String = {
    Option(errorClass).flatMap(errorClassToInfoMap.get).flatMap(_.sqlState).orNull
  }

  def isInternalError(errorClass: String): Boolean = {
    errorClass == "INTERNAL_ERROR"
  }
}
