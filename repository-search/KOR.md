# Phantom Repository Search

`dev.phantom.repository-search` · [English](./README.md)

Maven `pom.xml`과 Gradle 빌드 스크립트의 의존성 자동완성을 맡는 서비스(로컬 Maven 인덱스, Gradle 캐시, 프로젝트 모듈에서 그룹·아티팩트·버전 제안)와, Maven과 Gradle이 아티팩트 저장소를 검색할 때 쓰는 API. JetBrains의 Package Search 서비스(package-search.services.jetbrains.com)는 부르지 않습니다. Phantom IDEA에서처럼 클라이언트가 모든 요청을 보내기 전에 실패시킵니다. 사용 통계도 수집하지 않습니다.

IntelliJ Community의 Repository Search 플러그인(`plugins/repository-search`, Apache 2.0)을 바탕으로 Phantom의 id로 만든 플러그인입니다.
`org.jetbrains.idea.reposearch`는 별칭으로 남겨 두고 콘텐츠 모듈 이름도 IntelliJ 것을 그대로 써서, 이것에 의존하는 플러그인과
번들 모듈(Maven, Gradle, Kotlin)이 그대로 동작합니다. Phantom은 IntelliJ 플러그인 대신 이 빌드를 번들합니다.
