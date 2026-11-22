# Phantom for Groovy
`dev.phantom.groovy` · [English](./README.md)

Groovy: 하이라이팅, 자동완성, 인스펙션과 빠른 수정, 인텐션, 리팩터링, 빠른 문서, 포매팅, 구조 검색, GDSL 스크립트, 디버거 핫
스왑을 지원하는 Groovy·Gant 실행 구성, Groovy 콘솔, Grape 의존성, IDE 빌드에서의 컴파일(Groovy 컴파일러나 Eclipse 컴파일러,
스텁 생성), Spock·Geb 지원, Groovy 문자열 안의 언어 주입, 그리고 Ant·Git·Copyright 연동(각 플러그인이 있을 때
켜짐).

IntelliJ Community의 Groovy 플러그인(`plugins/groovy`, Apache 2.0)을 바탕으로 Phantom의 id로 만든 플러그인입니다.
`org.intellij.groovy`는 별칭으로 남겨 두고 콘텐츠 모듈 이름도 IntelliJ 것을 그대로 써서, 이것에 의존하는 플러그인(Gradle,
Maven, Groovy Live Templates 등)이 그대로 동작합니다. 플러그인 폴더 구성도 IntelliJ와 같습니다(`lib/groovy-jps.jar`,
`groovy-rt.jar`, `standardDsls/`, `agent/gragent.jar`, `modules/` 등). Phantom은 IntelliJ 플러그인 대신 이 빌드를 번들합니다.

**0.1.3**: 이름을 바꿨습니다: Groovy Support → Phantom for Groovy. Phantom으로 시작하고 지원하는 프로젝트 이름을 뒤에 둡니다. 플러그인 id와 기능은 그대로입니다.

**0.1.2**: 플러그인 jar에 번들된 HotSwap 에이전트(`lib/agent/gragent.jar`)가 포함한 ASM의 고지가
`META-INF/NOTICE-groovy`로 들어갑니다. 테스트 데이터에 있던 Sun JDK 5 사본("SUN PROPRIETARY/CONFIDENTIAL" 표시)을 지웠습니다. 테스트는
실행 중인 JDK와 Groovy 1.0(`testData/lib`)을 쓰고, 그 JDK 사본에 맞춘 자동완성 케이스 5개는 제외했습니다.
