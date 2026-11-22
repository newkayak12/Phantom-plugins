// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.spring

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiManager
import com.intellij.psi.SyntaxTraverser
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.phantom.spring.messaging.MessagingLineMarkerProvider
import dev.phantom.spring.messaging.MessagingModel
import dev.phantom.spring.messaging.MessagingSite
import junit.framework.TestCase

/** Kafka senders and receivers by topic, in Java and in the user's Kotlin style (topic constants, ProducerRecord). */
class SpringMessagingTest : LightJavaCodeInsightFixtureTestCase() {
  override fun getProjectDescriptor() = RealJdk

  override fun setUp() {
    super.setUp()
    SpringMvcEndpointsTest.springWebStubs().forEach { myFixture.addClass(it) }
    val runtime = "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)"
    myFixture.addClass("package org.springframework.kafka.annotation; $runtime public @interface KafkaListener { String[] topics() default {}; String id() default \"\"; }")
    myFixture.addClass("""
      package org.springframework.kafka.core;
      public class KafkaTemplate<K, V> {
        public Object send(String topic, V data) { return null; }
        public Object send(String topic, K key, V data) { return null; }
        public Object send(org.apache.kafka.clients.producer.ProducerRecord<K, V> record) { return null; }
      }
    """.trimIndent())
    myFixture.addClass("package org.apache.kafka.clients.producer; public class ProducerRecord<K, V> { public ProducerRecord(String topic, K key, V value) {} }")
    myFixture.addClass("package org.apache.kafka.clients.consumer; public interface Consumer<K, V> { void subscribe(java.util.Collection<String> topics); }")
    myFixture.addFileToProject("app/Topics.kt", """
      package app
      object KafkaTopic {
          const val ORDER_PLACED = "order.placed"
          const val ORDER_SHIPPED = "order.shipped"
      }
    """.trimIndent())
    myFixture.addFileToProject("app/Publisher.kt", """
      package app
      import org.apache.kafka.clients.producer.ProducerRecord
      import org.springframework.kafka.core.KafkaTemplate
      class Publisher(private val kafkaTemplate: KafkaTemplate<String, String>) {
          fun placed(key: String, body: String) { kafkaTemplate.send(ProducerRecord(KafkaTopic.ORDER_PLACED, key, body)) }
          fun shipped(body: String) { kafkaTemplate.send(KafkaTopic.ORDER_SHIPPED, body) }
      }
    """.trimIndent())
    myFixture.addClass("""
      package app;
      import org.springframework.kafka.annotation.KafkaListener;
      public class OrderListener {
        @KafkaListener(topics = {KafkaTopic.ORDER_PLACED, "order.cancelled"}) public void onPlaced(String body) {}
      }
    """.trimIndent())
    // Java: the light fixture has no Kotlin stdlib, so a Kotlin listOf(...) argument would not resolve the call
    myFixture.addClass("""
      package app;
      public class Poller {
        org.apache.kafka.clients.consumer.Consumer<String, String> consumer;
        public void start() { consumer.subscribe(java.util.List.of(KafkaTopic.ORDER_SHIPPED)); }
        public void retries(int attempts) {
          java.util.List<String> topics = new java.util.ArrayList<>();
          topics.add("order.retry");
          for (int i = 1; i <= attempts; i++) topics.add(KafkaTopic.ORDER_SHIPPED + "-RETRY-" + i);
          consumer.subscribe(topics);
        }
      }
    """.trimIndent())
  }

  fun testSitesByTopic() {
    val sites = MessagingModel.all(project).map { "${if (it.sends) "send" else "recv"} ${it.channel} ${it.label}" }.sorted()
    assertEquals(listOf(
      "recv order.cancelled OrderListener.onPlaced",
      "recv order.placed OrderListener.onPlaced",
      "recv order.retry Poller.retries",
      "recv order.shipped Poller.start",
      "send order.placed Publisher.placed",
      "send order.shipped Publisher.shipped",
    ), sites)
  }

  fun testGutters() {
    myFixture.configureFromTempProjectFile("app/Publisher.kt")
    myFixture.doHighlighting()
    val tooltips = myFixture.findAllGutters().mapNotNull { runCatching { it.tooltipText }.getOrNull() }
    assertTrue(tooltips.toString(), "Receivers of order.placed" in tooltips)
    assertTrue(tooltips.toString(), "Receivers of order.shipped" in tooltips)

    myFixture.configureFromExistingVirtualFile(myFixture.findClass("app.OrderListener").containingFile.virtualFile)
    myFixture.doHighlighting()
    assertTrue("Senders to order.placed, order.cancelled" in myFixture.findAllGutters().mapNotNull { it.tooltipText })
  }

  fun testPlaceholdersResolveFromConfig() {
    myFixture.addFileToProject("application.yml", """
      spring:
        kafka:
          bootstrap-servers: localhost:9092 # local broker
      app:
        topics:
          refund: "order.refunded"
    """.trimIndent())
    myFixture.addFileToProject("application-local.properties", "app.topics.audit=order.audit\n")
    myFixture.addClass("""
      package app;
      import org.springframework.kafka.annotation.KafkaListener;
      public class RefundListener {
        @KafkaListener(topics = "${'$'}{app.topics.refund}") public void onRefund(String body) {}
        @KafkaListener(topics = "${'$'}{app.topics.audit}") public void onAudit(String body) {}
        @KafkaListener(topics = "${'$'}{app.topics.missing:order.fallback}") public void onFallback(String body) {}
        @KafkaListener(topics = "${'$'}{app.topics.unknown}") public void onUnknown(String body) {}
      }
    """.trimIndent())
    myFixture.addClass("""
      package app;
      public class RefundPublisher {
        org.springframework.kafka.core.KafkaTemplate<String, String> template;
        public void refund(String body) { template.send("order.refunded", body); }
      }
    """.trimIndent())
    val received = MessagingModel.all(project).filter { !it.sends && it.label.startsWith("RefundListener") }.map { it.channel }.sorted()
    assertEquals(listOf("\${app.topics.unknown}", "order.audit", "order.fallback", "order.refunded"), received)
    myFixture.configureFromExistingVirtualFile(myFixture.findClass("app.RefundPublisher").containingFile.virtualFile)
    myFixture.doHighlighting()
    assertTrue("Receivers of order.refunded" in myFixture.findAllGutters().mapNotNull { it.tooltipText })
    assertTrue(assertLocalSitesMatch(project) > 0)
  }

  fun testTopicCompletion() {
    myFixture.configureByText("Listener.java", """
      import org.springframework.kafka.annotation.KafkaListener;
      class Listener { @KafkaListener(topics = "order.<caret>") void on(String s) {} }
    """.trimIndent())
    val items = myFixture.completeBasic()?.map { it.lookupString }.orEmpty()
    assertEquals(listOf("order.cancelled", "order.placed", "order.retry", "order.shipped"), items.sorted())
  }

  fun testLocalSitesMatchProjectSearch() {
    // an override, a subclass without one, and an overload the strict search leaves out
    myFixture.addClass("""
      package app;
      public class MyTemplate extends org.springframework.kafka.core.KafkaTemplate<String, String> {
        @Override public Object send(String topic, String data) { return null; }
        public Object send(String topic, String a, String b, String c) { return null; }
      }
    """.trimIndent())
    myFixture.addClass("package app; public class PlainTemplate extends org.springframework.kafka.core.KafkaTemplate<String, String> {}")
    myFixture.addClass("""
      package app;
      import org.springframework.kafka.annotation.KafkaListener;
      @KafkaListener(topics = "order.class")
      public class Senders {
        MyTemplate mine; PlainTemplate plain;
        public void a(String b) { mine.send("order.override", b); }
        public void c(String b) { plain.send("order.plain", b); }
        public void d() { mine.send("order.overload", "a", "b", "c"); }
        public void e() { new org.apache.kafka.clients.producer.ProducerRecord<String, String>("order.record", "k", "v"); }
        public void f() { new org.apache.kafka.clients.producer.ProducerRecord<String, String>("order.anonymous", "k", "v") {}; }
        public Runnable g() {
          return new Runnable() { @KafkaListener(topics = "order.anon") public void on(String s) {} public void run() {} };
        }
      }
    """.trimIndent())
    val markers = assertLocalSitesMatch(project)
    assertTrue("markers: $markers", markers >= 8)
  }

  fun testNoLocalSitesOutsideProjectSources() {
    val file = PsiFileFactory.getInstance(project).createFileFromText("Loose.java", com.intellij.lang.java.JavaLanguage.INSTANCE, """
      package app;
      public class Loose {
        org.springframework.kafka.core.KafkaTemplate<String, String> t;
        @org.springframework.kafka.annotation.KafkaListener(topics = "order.loose") public void on(String s) { t.send("order.loose", s); }
      }
    """.trimIndent())
    val provider = MessagingLineMarkerProvider()
    val local = SyntaxTraverser.psiTraverser(file).filter { it.firstChild == null }.flatMap { provider.sites(it, MessagingModel::localSitesAt) }.toList()
    assertEmpty(local)
  }

  companion object {
    /**
     * The gutter sites found from each element alone equal the ones read off the project-wide list, on every leaf of
     * every Java and Kotlin source. Returns how many leaves have a marker, so a test can check it compared something.
     */
    fun assertLocalSitesMatch(project: Project): Int {
      val provider = MessagingLineMarkerProvider()
      val scope = GlobalSearchScope.projectScope(project)
      val files: List<PsiFile> = listOf("java", "kt").flatMap { FilenameIndex.getAllFilesByExt(project, it, scope) }
        .mapNotNull { PsiManager.getInstance(project).findFile(it) }
      var markers = 0
      for (file in files) {
        for (leaf in SyntaxTraverser.psiTraverser(file).filter { it.firstChild == null }) {
          val expected = provider.sites(leaf, MessagingModel::sitesAt).map(::describe).toSet()
          val actual = provider.sites(leaf, MessagingModel::localSitesAt).map(::describe).toSet()
          TestCase.assertEquals("${file.name}:${leaf.textRange.startOffset} ${leaf.text}", expected, actual)
          if (actual.isNotEmpty()) markers++
        }
      }
      return markers
    }

    private fun describe(site: MessagingSite) = "${if (site.sends) "send" else "recv"} ${site.broker} ${site.channel} ${site.label}"
  }
}
