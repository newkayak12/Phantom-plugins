// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.springsecurity

import com.intellij.testFramework.fixtures.JavaCodeInsightTestFixture

/** Source stubs of the Spring and Spring Security types a filter chain bean uses. */
object SecurityStubs {
  fun add(fixture: JavaCodeInsightTestFixture) {
    fixture.addClass("package org.springframework.context.annotation; public @interface Bean {}")
    fixture.addClass("package org.springframework.core.annotation; public @interface Order { int value(); }")
    fixture.addClass("package org.springframework.core; public interface Ordered { int HIGHEST_PRECEDENCE = Integer.MIN_VALUE; }")
    fixture.addClass("package org.springframework.http; public enum HttpMethod { GET, POST }")
    fixture.addClass("package org.springframework.security.web; public interface SecurityFilterChain {}")
    fixture.addClass("package org.springframework.security.config; public interface Customizer<T> { void customize(T t); }")
    fixture.addClass(
      """
      package org.springframework.security.config.annotation.web.builders;
      import org.springframework.http.HttpMethod;
      import org.springframework.security.config.Customizer;
      public class HttpSecurity {
        public HttpSecurity securityMatcher(String... patterns) { return this; }
        public HttpSecurity authorizeHttpRequests(Customizer<Registry> customizer) { return this; }
        public org.springframework.security.web.SecurityFilterChain build() { return null; }
        public static class Registry {
          public Url requestMatchers(String... patterns) { return null; }
          public Url requestMatchers(HttpMethod method, String... patterns) { return null; }
          public Url anyRequest() { return null; }
        }
        public static class Url {
          public Registry permitAll() { return null; }
          public Registry authenticated() { return null; }
          public Registry hasRole(String role) { return null; }
          public Registry denyAll() { return null; }
        }
      }
      """.trimIndent()
    )
  }
}
