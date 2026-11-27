// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.asyncapi

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.psi.PsiElement
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

class KafkaChannelTest : LightJavaCodeInsightFixtureTestCase() {
  companion object {
    init {
      System.setProperty("idea.load.plugins.id", "dev.phantom.asyncapi,org.jetbrains.plugins.yaml,com.intellij.modules.json,com.intellij.java")
    }
  }

  override fun getProjectDescriptor() = JAVA_LATEST

  override fun setUp() {
    super.setUp()
    myFixture.addClass("package org.springframework.kafka.annotation; public @interface KafkaListener { String[] topics() default {}; String id() default \"\"; }")
    myFixture.addClass(
      """
      package org.springframework.kafka.core;
      public class KafkaTemplate<K, V> {
        public Object send(String topic, V data) { return null; }
        public Object send(String topic, K key, V data) { return null; }
        public Object send(Object record) { return null; }
      }
      """.trimIndent(),
    )
    myFixture.addClass(
      """
      package app;
      import org.springframework.kafka.annotation.KafkaListener;
      import org.springframework.kafka.core.KafkaTemplate;
      public class Orders {
        static final String PAYMENTS = "payments";
        KafkaTemplate<String, String> template;
        @KafkaListener(topics = "orders") public void onOrder(String body) {}
        @KafkaListener(topics = {"audit", "unlisted"}) public void onAudit(String body) {}
        public void pay(String body) { template.send(PAYMENTS, "key", body); template.send(new Object()); }
        public void helper() {}
      }
      """.trimIndent(),
    )
    myFixture.addClass("package app; @org.springframework.kafka.annotation.KafkaListener(topics = \"audit\") public class AuditHandler {}")
  }

  private val v3 = """
    asyncapi: 3.0.0
    info:
      title: Shop
      version: 1.0.0
    channels:
      orderPlaced:
        address: orders
      paymentDone:
        address: payments-v1
        bindings:
          kafka:
            topic: payments
      dynamic:
        address: null
  """.trimIndent()

  fun testChannelsNameTheirTopics() {
    val file = myFixture.configureByText("shop.yaml", v3)
    assertEquals(listOf("orders", "payments"), AsyncApiDocs.channels(file).map { it.topic })
    val v2 = myFixture.configureByText("audit.json", """{"asyncapi": "2.6.0", "channels": {"audit": {}}}""")
    assertEquals(listOf("audit"), AsyncApiDocs.channels(v2).map { it.topic })
  }

  fun testCodeFindsListenersAndSendsWithConstantTopics() {
    val uses = KafkaCode.uses(project).map { it.topic to it.element.text }.sortedBy { it.toString() }
    assertEquals(listOf("audit" to "AuditHandler", "audit" to "onAudit", "orders" to "onOrder", "payments" to "send", "unlisted" to "onAudit"), uses)
  }

  fun testChannelGuttersGoToCode() {
    myFixture.configureByText("shop.yaml", v3)
    assertEquals(listOf("onOrder", "send"), gutterTargets().sorted())
  }

  fun testCodeGuttersGoToChannels() {
    myFixture.addFileToProject("shop.yaml", v3)
    myFixture.addFileToProject("audit.json", """{"asyncapi": "2.6.0", "channels": {"audit": {}}}""")
    myFixture.configureFromExistingVirtualFile(myFixture.findClass("app.Orders").containingFile.virtualFile)
    assertEquals(listOf("\"audit\"", "orderPlaced", "paymentDone"), gutterTargets().sorted())
    myFixture.configureFromExistingVirtualFile(myFixture.findClass("app.AuditHandler").containingFile.virtualFile)
    assertEquals(listOf("\"audit\""), gutterTargets())
  }

  fun testNoGutterOutsideSpecs() {
    myFixture.configureByText("app.yaml", "channels:\n  orders: {}\n")
    assertEmpty(gutterTargets())
  }

  private fun gutterTargets(): List<String> = myFixture.findAllGutters().flatMap { g ->
    val info = (g as? LineMarkerInfo.LineMarkerGutterIconRenderer<*>)?.lineMarkerInfo as? RelatedItemLineMarkerInfo<*> ?: return@flatMap emptyList()
    info.createGotoRelatedItems().mapNotNull { (it.element as? PsiElement)?.text }
  }
}
