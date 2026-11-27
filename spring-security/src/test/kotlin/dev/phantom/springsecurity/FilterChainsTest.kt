// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.springsecurity

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.phantom.springsecurity.core.FilterChainLineMarkerProvider
import dev.phantom.springsecurity.core.FilterChains
import dev.phantom.springsecurity.core.RuleAfterAnyRequestInspection
import dev.phantom.springsecurity.core.ShadowedFilterChainInspection

class FilterChainsTest : LightJavaCodeInsightFixtureTestCase() {
  override fun getProjectDescriptor() = RealJdk

  override fun setUp() {
    super.setUp()
    SecurityStubs.add(myFixture)
    myFixture.enableInspections(RuleAfterAnyRequestInspection::class.java, ShadowedFilterChainInspection::class.java)
  }

  private val config = """
    import org.springframework.context.annotation.Bean;
    import org.springframework.core.Ordered;
    import org.springframework.core.annotation.Order;
    import org.springframework.http.HttpMethod;
    import org.springframework.security.config.annotation.web.builders.HttpSecurity;
    import org.springframework.security.web.SecurityFilterChain;

    public class SecurityConfig {
        static final String ADMIN = "/admin/**";

        @Bean
        SecurityFilterChain web(HttpSecurity http) {
            return http.authorizeHttpRequests(auth -> auth
                .requestMatchers("/", "/login").permitAll()
                .requestMatchers(HttpMethod.POST, ADMIN).hasRole("ADMIN")
                .anyRequest().authenticated()).build();
        }

        @Bean
        @Order(Ordered.HIGHEST_PRECEDENCE)
        SecurityFilterChain api(HttpSecurity http) {
            return http.securityMatcher("/api/**")
                .authorizeHttpRequests(auth -> auth.anyRequest().denyAll()).build();
        }

        SecurityFilterChain notABean(HttpSecurity http) { return http.build(); }
    }
  """.trimIndent()

  private fun problems(): List<String> = myFixture.doHighlighting(HighlightSeverity.ERROR)
    .filter { it.inspectionToolId?.startsWith("Phantom") == true }
    .map { "${myFixture.file.text.substring(it.startOffset, it.endOffset)}: ${it.description}" }

  fun testChainsInOrderWithMatchersAndRules() {
    myFixture.configureByText("SecurityConfig.java", config)
    val chains = FilterChains.all(project)
    assertEquals(listOf("api", "web"), chains.map { it.method.name })
    assertEquals(listOf(Int.MIN_VALUE, null), chains.map { it.order })
    assertEquals(listOf("/api/**"), chains[0].securityMatchers)
    assertTrue(chains[1].matchesEveryRequest)
    assertEquals(
      listOf("/, /login → permitAll()", "POST /admin/** → hasRole(\"ADMIN\")", "anyRequest() → authenticated()"),
      chains[1].rules.map(FilterChainLineMarkerProvider::describe),
    )
    assertEquals(emptyList<String>(), problems())
  }

  fun testGutterOnBeanMethodsOnly() {
    myFixture.configureByText("SecurityConfig.java", config)
    myFixture.doHighlighting()
    val marked = myFixture.findAllGutters().mapNotNull { it.tooltipText }.filter { it.contains("Filter chain") }
    assertEquals(2, marked.size)
    assertTrue(marked.any { it.contains("Filter chain 2 of 2, order default (last)") && it.contains("Applies to: every request") })
    assertTrue(marked.any { it.contains("Filter chain 1 of 2") && it.contains("Applies to: /api/**") })
  }

  fun testRuleAfterAnyRequest() {
    myFixture.configureByText("Late.java", """
      import org.springframework.context.annotation.Bean;
      import org.springframework.security.config.annotation.web.builders.HttpSecurity;
      import org.springframework.security.web.SecurityFilterChain;

      public class Late {
          @Bean
          SecurityFilterChain web(HttpSecurity http) {
              return http.authorizeHttpRequests(auth -> auth
                  .anyRequest().authenticated()
                  .requestMatchers("/public").permitAll()).build();
          }
      }
    """.trimIndent())
    assertEquals(listOf("requestMatchers: Spring Security fails at startup: requestMatchers cannot come after anyRequest()"), problems())
  }

  fun testChainAfterAnyRequestChainIsShadowed() {
    myFixture.configureByText("Shadow.java", """
      import org.springframework.context.annotation.Bean;
      import org.springframework.core.annotation.Order;
      import org.springframework.security.config.annotation.web.builders.HttpSecurity;
      import org.springframework.security.web.SecurityFilterChain;

      public class Shadow {
          @Bean @Order(1)
          SecurityFilterChain everything(HttpSecurity http) { return http.build(); }

          @Bean @Order(2)
          SecurityFilterChain api(HttpSecurity http) { return http.securityMatcher("/api/**").build(); }
      }
    """.trimIndent())
    assertEquals(
      listOf("api: Never reached: filter chain 'everything' (order 1) has no securityMatcher and matches every request first"),
      problems(),
    )
  }
}
