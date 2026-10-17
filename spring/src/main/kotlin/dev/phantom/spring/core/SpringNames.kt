// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.spring.core

import org.jetbrains.annotations.NonNls

@NonNls
object SpringNames {
  const val SPRING_CONTEXT_MAVEN: String = "org.springframework:spring-context"

  const val COMPONENT: String = "org.springframework.stereotype.Component"
  const val BEAN: String = "org.springframework.context.annotation.Bean"
  const val PRIMARY: String = "org.springframework.context.annotation.Primary"
  const val AUTOWIRED: String = "org.springframework.beans.factory.annotation.Autowired"
  const val VALUE: String = "org.springframework.beans.factory.annotation.Value"
  const val QUALIFIER: String = "org.springframework.beans.factory.annotation.Qualifier"
  const val CONFIGURATION_PROPERTIES: String = "org.springframework.boot.context.properties.ConfigurationProperties"

  const val DATA_REPOSITORY: String = "org.springframework.data.repository.Repository"
  const val NO_REPOSITORY_BEAN: String = "org.springframework.data.repository.NoRepositoryBean"

  val INJECT_MARKERS: List<String> = listOf(
    AUTOWIRED,
    "jakarta.inject.Inject",
    "javax.inject.Inject",
    "jakarta.annotation.Resource",
    "javax.annotation.Resource",
  )

  val NAMED_QUALIFIERS: List<String> = listOf(QUALIFIER, "jakarta.inject.Named", "javax.inject.Named")

  /** Methods annotated with these (directly or through a meta-annotation) are called by the framework. */
  val FRAMEWORK_CALLED_METHOD_MARKERS: List<String> = listOf(
    BEAN,
    AUTOWIRED,
    "org.springframework.context.event.EventListener",
    "org.springframework.scheduling.annotation.Scheduled",
    "org.springframework.web.bind.annotation.RequestMapping",
    "org.springframework.web.bind.annotation.ExceptionHandler",
    "org.springframework.web.bind.annotation.ModelAttribute",
    "org.springframework.web.bind.annotation.InitBinder",
    "org.springframework.messaging.handler.annotation.MessageMapping",
    "org.springframework.kafka.annotation.KafkaListener",
    "org.springframework.amqp.rabbit.annotation.RabbitListener",
    "org.springframework.jms.annotation.JmsListener",
    "jakarta.annotation.PostConstruct",
    "jakarta.annotation.PreDestroy",
    "javax.annotation.PostConstruct",
    "javax.annotation.PreDestroy",
  )

  /** Wrapper types whose single type argument is the bean type that gets injected. */
  val SINGLE_ARG_WRAPPERS: Set<String> = setOf(
    "java.util.Optional",
    "java.util.List",
    "java.util.Set",
    "java.util.Collection",
    "java.lang.Iterable",
    "java.util.SortedSet",
    "org.springframework.beans.factory.ObjectProvider",
    "org.springframework.beans.factory.ObjectFactory",
    "jakarta.inject.Provider",
    "javax.inject.Provider",
  )

  const val MAP: String = "java.util.Map"
}
