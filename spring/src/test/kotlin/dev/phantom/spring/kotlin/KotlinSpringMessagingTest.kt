// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.spring.kotlin

import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.phantom.spring.RealJdkWithKotlin
import dev.phantom.spring.SpringMvcEndpointsTest
import dev.phantom.spring.messaging.MessagingModel

/** The user's consumer style: topic lists built in Kotlin, with retry topics derived at runtime. Needs the real stdlib. */
class KotlinSpringMessagingTest : LightJavaCodeInsightFixtureTestCase() {
  override fun getProjectDescriptor() = RealJdkWithKotlin

  fun testTopicListsBuiltInKotlin() {
    SpringMvcEndpointsTest.springWebStubs().forEach { myFixture.addClass(it) }
    myFixture.addClass("package org.springframework.kafka.core; public class KafkaTemplate<K, V> { public Object send(String t, V d) { return null; } }")
    myFixture.addClass("package io.confluent.parallelconsumer; public interface ParallelConsumer<K, V> { void subscribe(java.util.Collection<String> topics); }")
    myFixture.addClass("package io.confluent.parallelconsumer; public interface ParallelStreamProcessor<K, V> extends ParallelConsumer<K, V> {}")
    myFixture.addFileToProject("app/Listeners.kt", """
      package app
      import io.confluent.parallelconsumer.ParallelStreamProcessor

      object KafkaTopic {
          const val OCCUPIED = "time-table.occupied"
          const val REQUESTED = "time-table.requested"
      }

      class OccupiedListener(private val consumer: ParallelStreamProcessor<String, String>) {
          companion object { const val TOPIC = KafkaTopic.OCCUPIED }
          fun init() {
              val topics = mutableListOf<String>()
              repeat(3) {
                  when (it) {
                      0 -> topics.add(TOPIC)
                      else -> topics.add("${'$'}TOPIC-RETRY-${'$'}it")
                  }
              }
              consumer.subscribe(topics)
          }
      }

      class RequestedListener(private val consumer: ParallelStreamProcessor<String, String>) {
          companion object { const val TOPIC = KafkaTopic.REQUESTED }
          fun init() { consumer.subscribe(subscribedTopics()) }
          private fun subscribedTopics(): List<String> = (0 until 3).map { if (it == 0) TOPIC else "${'$'}TOPIC-RETRY-${'$'}it" }
      }
    """.trimIndent())
    val sites = MessagingModel.all(project).map { "${it.channel} ${it.label}" }.sorted()
    assertEquals(listOf("time-table.occupied OccupiedListener.init", "time-table.requested RequestedListener.init"), sites)
  }
}
