# Groovy Scripting Support

`dev.phantom.groovy-scripting` · [English](./README.md)

IDE Scripting Console에서 쓰는 Groovy JSR223 스크립팅 엔진과, Groovy·Groovy Live Templates 등 다른 플러그인이
`intellij.libraries.groovy` 모듈로 불러 쓰는 Groovy 3 라이브러리(Grape용 Apache Ivy 포함).

IntelliJ Community의 Groovy Scripting 플러그인(`plugins/groovy-scripting`과 `libraries/groovy`, `libraries/groovy/jsr223`,
`libraries/ivy`, Apache 2.0)을 바탕으로 Phantom의 id로 만든 플러그인입니다. `com.intellij.groovy.scripting`은 별칭으로 남겨
두고 라이브러리 모듈 이름도 IntelliJ 것을 그대로 써서, 이것에 의존하는 플러그인이 그대로 동작합니다. Phantom은 IntelliJ
플러그인 대신 이 빌드를 번들합니다.

**0.1.1**: 플러그인 jar의 `META-INF/THIRD-PARTY-NOTICES.txt`에 포함된 라이브러리(Apache Groovy, Apache Ivy)와 그 라이선스, NOTICE
파일이 나옵니다.
