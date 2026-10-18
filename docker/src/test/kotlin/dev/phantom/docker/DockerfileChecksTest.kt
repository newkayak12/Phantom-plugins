// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.docker

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import dev.phantom.docker.compose.PhantomDockerBundle
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.phantom.docker.dockerfile.*

class DockerfileChecksTest : BasePlatformTestCase() {
  override fun setUp() {
    super.setUp()
    myFixture.enableInspections(DockerfileInspectionImpl::class.java, DockerfileShellFormInspection::class.java, DockerfileCopyPathsInspection::class.java)
  }

  private fun problems(): List<String> = myFixture.doHighlighting().filter { it.severity >= HighlightSeverity.WEAK_WARNING }
    .mapNotNull(HighlightInfo::getDescription).sorted()

  private fun dockerfile(path: String, text: String) {
    val file = myFixture.addFileToProject(path, text.trimIndent())
    myFixture.configureFromExistingVirtualFile(file.virtualFile)
  }

  fun testCleanMultiStageBuild() {
    myFixture.addFileToProject("app/build.gradle.kts", "")
    myFixture.addFileToProject("app/src/main/kotlin/App.kt", "")
    myFixture.addFileToProject("app/gradle/wrapper/gradle-wrapper.properties", "")
    dockerfile("app/Dockerfile", """
      # syntax=docker/dockerfile:1
      ARG JDK=21
      FROM eclipse-temurin:${'$'}{JDK}-jdk AS build
      WORKDIR /src
      COPY build.gradle.kts ./
      COPY gradle gradle
      COPY src src
      RUN --mount=type=cache,target=/root/.gradle \
          ./gradlew bootJar --no-daemon && \
          # comments inside a continuation are skipped
          ls build/libs
      RUN <<EOF
      set -e
      echo built
      EOF

      FROM eclipse-temurin:21-jre
      LABEL org.opencontainers.image.source="https://example.com" maintainer=me
      ENV JAVA_OPTS="-Xmx512m" TZ=Asia/Seoul
      ENV LEGACY value with spaces
      COPY --from=build /src/build/libs/*.jar /app/app.jar
      COPY --from=0 /src/gradle /tmp/gradle
      EXPOSE 8080
      USER 1000
      HEALTHCHECK CMD curl -f http://localhost:8080/actuator/health || exit 1
      ENTRYPOINT ["java", "-jar", "/app/app.jar"]
    """)
    assertEmpty(problems())
  }

  fun testBuilderErrors() {
    dockerfile("Dockerfile", """
      FROM alpine AS base
      FORM alpine
      FROM alpine AS Base
      RUN apk add curl
        && rm -rf /var/cache/apk
      COPY onlyone
      COPY --from=5 /a /b
      WORKDIR /a /b
      ARG 1bad
      LABEL version
      ENV A=1 B
      SHELL /bin/bash -c
      ONBUILD FROM x
      CMD ['run', 'it']
      RUN <<EOT
      echo never closed
    """)
    assertEquals(listOf(
      "--from=5 refers to this or a later stage; only earlier stages can be copied from",
      "ARG takes one name, optionally with =default",
      "Another stage is already named 'Base'",
      "COPY needs at least one source and a destination",
      "ENV takes key=value pairs, or a single key followed by its value",
      "Heredoc 'EOT' is never closed: no line holds only 'EOT'",
      "LABEL takes key=value pairs",
      "ONBUILD must be followed by an instruction other than ONBUILD, FROM or MAINTAINER",
      "RUN ends here: the indented line below is read as a new instruction. End this line with \\",
      "SHELL takes a JSON array, such as [\"/bin/bash\", \"-c\"]",
      "This is not a JSON array (strings need double quotes), so CMD runs it through the shell as text",
      "Unknown instruction 'FORM'",
      "WORKDIR takes exactly one argument",
    ), problems())
  }

  fun testShellFormAndCopySources() {
    myFixture.addFileToProject("svc/present.txt", "")
    myFixture.addFileToProject("svc/conf/a.yaml", "")
    myFixture.addFileToProject("shared.txt", "")
    dockerfile("svc/Dockerfile", """
      FROM alpine
      COPY present.txt conf/*.yaml /etc/
      COPY missing.txt /etc/
      COPY conf/*.json /etc/
      ADD https://example.com/x.tgz /tmp/
      COPY ${'$'}{SRC} /x
      COPY shared.txt /x
      CMD run --flag
      ENTRYPOINT exec java -jar app.jar
    """)
    assertEquals(listOf(
      "'conf/*.json' is not in the build context (svc)",
      "'missing.txt' is not in the build context (svc)",
      "CMD in shell form runs under /bin/sh -c: the process is not PID 1 and does not get SIGTERM. Use the exec form [\"executable\", \"arg\"]",
      "ENTRYPOINT in shell form runs under /bin/sh -c: the process is not PID 1 and does not get SIGTERM. Use the exec form [\"executable\", \"arg\"]",
    ), problems())
  }

  fun testCopyHeredocs() {
    myFixture.enableInspections(DockerfileCopyHeredocInspection::class.java)
    dockerfile("h/Dockerfile", """
      FROM alpine AS base
      COPY <<EOF /etc/app.conf
      key=value
      EOF
      COPY <<EOF
      lost
      EOF
      COPY --from=base <<EOF /x
      y
      EOF
    """)
    val heredoc = problems().filter { it.contains("here-document") }
    assertEquals(listOf(
      PhantomDockerBundle.message("dockerfile.heredoc.from", "COPY"),
      PhantomDockerBundle.message("dockerfile.heredoc.no.destination", "COPY"),
    ).sorted(), heredoc)

    dockerfile("old/Dockerfile", """
      # syntax=docker/dockerfile:1.3
      FROM alpine
      COPY <<EOF /etc/app.conf
      a
      EOF
    """)
    assertEquals(listOf(PhantomDockerBundle.message("dockerfile.heredoc.syntax", "1.3")), problems().filter { it.contains("syntax") })

    dockerfile("new/Dockerfile", """
      # syntax=docker/dockerfile:1.7
      FROM alpine
      COPY <<EOF /etc/app.conf
      a
      EOF
      RUN <<EOF
      echo hi
      EOF
    """)
    assertEmpty(problems().filter { it.contains("here-document") || it.contains("syntax") })
  }

  fun testComposeBuildContext() {
    myFixture.addFileToProject("compose.yaml", "services:\n  api:\n    build:\n      context: .\n      dockerfile: docker/api.Dockerfile\n")
    myFixture.addFileToProject("settings.gradle.kts", "")
    dockerfile("docker/api.Dockerfile", "FROM alpine\nCOPY settings.gradle.kts /src/\nCOPY nowhere.kts /src/\n")
    assertEquals(listOf("'nowhere.kts' is not in the build context (.)"), problems())
  }

  fun testOnlyDockerfileNames() {
    myFixture.configureByText("notes.txt", "FORM alpine\nCMD run\n")
    assertEmpty(problems())
    assertTrue(Dockerfile.isDockerfile("Dockerfile.dev"))
    assertTrue(Dockerfile.isDockerfile("api.Dockerfile"))
    assertTrue(Dockerfile.isDockerfile("Containerfile"))
    assertFalse(Dockerfile.isDockerfile("Dockerfiles.md"))
  }
}
