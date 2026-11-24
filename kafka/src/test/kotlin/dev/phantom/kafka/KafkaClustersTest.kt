// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.kafka

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Saving a cluster keeps the password safe out of it: the panel writes the properties on its executor, off the EDT. */
class KafkaClustersTest : BasePlatformTestCase() {
  fun testSaveAndRemoveLeaveThePropertiesToStoreProperties() {
    val clusters = KafkaClusters.getInstance(project)
    val cluster = KafkaCluster().apply { id = "test-cluster"; bootstrap = "broker:9092" }
    clusters.storeProperties(cluster.id, "security.protocol=SSL")

    clusters.save(cluster)
    assertEquals(listOf("test-cluster"), clusters.clusters.map { it.id })
    assertEquals("security.protocol=SSL", clusters.properties(cluster.id))

    clusters.remove(cluster.id)
    assertEquals("security.protocol=SSL", clusters.properties(cluster.id))
    clusters.storeProperties(cluster.id, " ")
    assertNull(clusters.properties(cluster.id))
  }
}
