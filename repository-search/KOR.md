# Phantom Repository Search

`dev.phantom.repository-search` · [English](./README.md)

Maven `pom.xml`과 Gradle 빌드 스크립트의 의존성 자동완성을 맡는 서비스(로컬 Maven 인덱스, Gradle 캐시, 프로젝트 모듈에서 그룹·아티팩트·버전 제안)와, Maven과 Gradle이 아티팩트 저장소를 검색할 때 쓰는 API. JetBrains의 Package Search 서비스(package-search.services.jetbrains.com)는 부르지 않습니다. 그 클라이언트는 플러그인에 들어 있지 않습니다. 사용 통계도 수집하지 않습니다.

IntelliJ Community의 Repository Search 플러그인(`plugins/repository-search`, Apache 2.0)을 바탕으로 Phantom의 id로 만든 플러그인입니다.
`org.jetbrains.idea.reposearch`는 별칭으로 남겨 두고 콘텐츠 모듈 이름도 IntelliJ 것을 그대로 써서, 이것에 의존하는 플러그인과
번들 모듈(Maven, Gradle, Kotlin)이 그대로 동작합니다. Phantom은 IntelliJ 플러그인 대신 이 빌드를 번들합니다.

**0.1.1**: Package Search 클라이언트를 JetBrains의 maven-deps-search.labs.jb.gg로 돌리던 숨은 레지스트리 스위치를 없앴습니다.
클라이언트는 여전히 모든 요청을 나가기 전에 실패시킵니다. 플러그인 jar의 `META-INF/THIRD-PARTY-NOTICES.txt`에 들어 있는
라이브러리(Package Search API 클라이언트, krypto, kotlinx-document-store)와 라이선스 목록이 있습니다.

**0.1.2**: Package Search 클라이언트(`PackageSearchApiClientService`)와 그것이 쓰던 라이브러리(Package Search API 클라이언트, krypto, kotlinx-document-store)를 뺐습니다. 이 라이브러리들은 JetBrains 전용 Maven 저장소에서 받아야 했고, Phantom에서 이 클라이언트를 부르는 곳이 없었습니다. 요청도 원래 보내기 전에 실패했습니다. `maven.packagesearch.enabled` 레지스트리 키도 없앴습니다.
