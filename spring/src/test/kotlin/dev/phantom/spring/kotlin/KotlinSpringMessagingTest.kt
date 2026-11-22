// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.spring.kotlin

import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.phantom.spring.RealJdkWithKotlin
import dev.phantom.spring.SpringMessagingTest
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
    assertEquals(2, SpringMessagingTest.assertLocalSitesMatch(project))
  }

  /** Gutter sites found per element match the project-wide search for Kotlin overrides, constructors and listeners. */
  fun testLocalSitesMatchProjectSearch() {
    SpringMvcEndpointsTest.springWebStubs().forEach { myFixture.addClass(it) }
    val runtime = "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)"
    myFixture.addClass("package org.springframework.kafka.annotation; $runtime public @interface KafkaListener { String[] topics() default {}; }")
    myFixture.addClass("""
      package org.springframework.kafka.core;
      public class KafkaTemplate<K, V> {
        public Object send(String topic, V data) { return null; }
        public Object send(String topic, K key, V data) { return null; }
      }
    """.trimIndent())
    myFixture.addClass("package org.apache.kafka.clients.producer; public class ProducerRecord<K, V> { public ProducerRecord(String topic, K key, V value) {} }")
    myFixture.addFileToProject("app/Templates.kt", """
      package app
      import org.apache.kafka.clients.producer.ProducerRecord
      import org.springframework.kafka.annotation.KafkaListener
      import org.springframework.kafka.core.KafkaTemplate

      open class MyTemplate : KafkaTemplate<String, String>() {
          override fun send(topic: String, data: String): Any? = null
          fun send(topic: String, a: String, b: String, c: String): Any? = null
      }
      class PlainTemplate : KafkaTemplate<String, String>()
      class MyRecord(topic: String) : ProducerRecord<String, String>(topic, "k", "v")

      @KafkaListener(topics = ["k.class"])
      class Senders(private val mine: MyTemplate, private val plain: PlainTemplate, private val base: KafkaTemplate<String, String>) {
          fun a(b: String) { mine.send("k.override", b) }
          fun c(b: String) { plain.send("k.plain", b) }
          fun d() { mine.send("k.overload", "a", "b", "c") }
          fun e(b: String) { base.send("k.base", "key", b) }
          fun f() = ProducerRecord<String, String>("k.record", "k", "v")
          fun g() = object : Runnable {
              @KafkaListener(topics = ["k.anon"]) fun on(s: String) {}
              override fun run() {}
          }
          @KafkaListener(topics = ["k.method"]) fun on(s: String) {}
      }
    """.trimIndent())
    myFixture.addClass("""
      package app;
      public class JavaSender {
        MyTemplate mine;
        public void a(String b) { mine.send("k.fromJava", b); }
      }
    """.trimIndent())
    val markers = SpringMessagingTest.assertLocalSitesMatch(project)
    assertTrue("markers: $markers", markers >= 6)
  }
}
