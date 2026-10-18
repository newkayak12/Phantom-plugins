// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.spring

import com.intellij.testFramework.fixtures.JavaCodeInsightTestFixture

/** Source stubs of the Spring annotations and types the core inspections look at. */
object SpringStubs {
  private const val RUNTIME = "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)"

  fun add(fixture: JavaCodeInsightTestFixture) {
    listOf(
      "package org.springframework.stereotype; $RUNTIME public @interface Component { String value() default \"\"; }",
      "package org.springframework.stereotype; $RUNTIME @Component public @interface Service { String value() default \"\"; }",
      "package org.springframework.context.annotation; $RUNTIME @org.springframework.stereotype.Component public @interface Configuration { boolean proxyBeanMethods() default true; }",
      "package org.springframework.context.annotation; $RUNTIME public @interface Bean { String[] name() default {}; String[] value() default {}; }",
      "package org.springframework.context.annotation; $RUNTIME public @interface Scope { String value() default \"\"; String scopeName() default \"\"; }",
      "package org.springframework.context.annotation; $RUNTIME public @interface DependsOn { String[] value() default {}; }",
      "package org.springframework.context.annotation; $RUNTIME public @interface Conditional { Class<?>[] value(); }",
      "package org.springframework.context.annotation; $RUNTIME @Conditional({}) public @interface Profile { String[] value(); }",
      "package org.springframework.beans.factory.annotation; $RUNTIME @java.lang.annotation.Target({java.lang.annotation.ElementType.CONSTRUCTOR, java.lang.annotation.ElementType.METHOD, java.lang.annotation.ElementType.PARAMETER, java.lang.annotation.ElementType.FIELD, java.lang.annotation.ElementType.ANNOTATION_TYPE}) public @interface Autowired { boolean required() default true; }",
      "package org.springframework.beans.factory.annotation; $RUNTIME public @interface Qualifier { String value() default \"\"; }",
      "package org.springframework.beans.factory.annotation; $RUNTIME public @interface Value { String value(); }",
      "package org.springframework.transaction.annotation; public enum Propagation { REQUIRED, REQUIRES_NEW, NESTED }",
      "package org.springframework.transaction.annotation; $RUNTIME public @interface Transactional { Propagation propagation() default Propagation.REQUIRED; boolean readOnly() default false; }",
      "package org.springframework.scheduling.annotation; $RUNTIME public @interface Async { String value() default \"\"; }",
      "package org.springframework.scheduling.annotation; $RUNTIME public @interface Scheduled { String cron() default \"\"; long fixedDelay() default -1; String fixedDelayString() default \"\"; long fixedRate() default -1; String fixedRateString() default \"\"; long initialDelay() default -1; String initialDelayString() default \"\"; }",
      "package org.springframework.cache.annotation; $RUNTIME public @interface Cacheable { String[] value() default {}; String[] cacheNames() default {}; String key() default \"\"; String condition() default \"\"; String unless() default \"\"; String cacheResolver() default \"\"; }",
      "package org.springframework.cache.annotation; $RUNTIME public @interface CachePut { String[] value() default {}; String[] cacheNames() default {}; String key() default \"\"; String condition() default \"\"; String unless() default \"\"; String cacheResolver() default \"\"; }",
      "package org.springframework.cache.annotation; $RUNTIME public @interface CacheEvict { String[] value() default {}; String[] cacheNames() default {}; String key() default \"\"; String condition() default \"\"; String unless() default \"\"; String cacheResolver() default \"\"; boolean allEntries() default false; }",
      "package org.springframework.cache.annotation; $RUNTIME public @interface CacheConfig { String[] cacheNames() default {}; String cacheResolver() default \"\"; }",
      "package org.springframework.cache.interceptor; public interface CacheResolver {}",
      "package org.springframework.context.event; $RUNTIME public @interface EventListener { Class<?>[] value() default {}; Class<?>[] classes() default {}; String condition() default \"\"; }",
      "package org.springframework.transaction.event; public enum TransactionPhase { BEFORE_COMMIT, AFTER_COMMIT }",
      "package org.springframework.transaction.event; $RUNTIME @org.springframework.context.event.EventListener public @interface TransactionalEventListener { TransactionPhase phase() default TransactionPhase.AFTER_COMMIT; Class<?>[] classes() default {}; }",
      "package org.springframework.context; public interface ApplicationEventPublisher { default void publishEvent(Object event) {} }",
      "package org.springframework.test.context; $RUNTIME public @interface BootstrapWith { Class<?> value() default Object.class; }",
      "package org.springframework.boot.test.context; $RUNTIME @org.springframework.test.context.BootstrapWith public @interface SpringBootTest {}",
      "package org.springframework.test.annotation; $RUNTIME public @interface DirtiesContext {}",
      "package org.springframework.test.context.jdbc; $RUNTIME public @interface Sql { String[] value() default {}; String[] scripts() default {}; }",
    ).forEach { fixture.addClass(it) }
  }
}
