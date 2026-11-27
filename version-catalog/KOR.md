# Phantom for Gradle Version Catalogs

`dev.phantom.version.catalog` · [English](./README.md)

Gradle 버전 카탈로그(`gradle/libs.versions.toml` 또는 `*.versions.toml`)의 라이브러리에 Maven Central의 새 버전이 있는지
확인합니다.

- 편집기나 Project 보기에서 카탈로그를 오른쪽 클릭하고 **Check for Newer Versions on Maven Central**을 고릅니다.
- 새 버전이 있는 라이브러리와 `[versions]` 항목이 대화 상자에 나오고, 체크한 항목이 한 번에 되돌릴 수 있는 명령으로 파일에
  쓰입니다.
- 사전 배포 버전(alpha, beta, RC, milestone, snapshot)은 지금 버전이 사전 배포일 때만 제안합니다.
- 여러 라이브러리가 함께 쓰는 `[versions]` 항목은 그 라이브러리 모두에 있는 가장 새 버전까지만 올립니다.

Maven Central(`repo1.maven.org`)에는 이 동작을 실행할 때만 묻습니다. 시작할 때나 백그라운드에서는 아무것도 하지 않습니다.
Maven Central에 없는 라이브러리, `[plugins]` 항목(Gradle Plugin Portal), 리치 버전(`strictly`, `require`), 동적 버전(`1.+`,
범위)은 건너뜁니다.

`libs.*`과 카탈로그 항목 사이 이동, `version.ref` 해석, 이름 바꾸기, 쓰지 않는 항목 검사는 이 플러그인이 아니라 Phantom의
Gradle 지원이 제공합니다.

Gradle은 Gradle, Inc.의 상표이며, 이 플러그인은 Gradle, Inc.와 관계가 없고 보증을 받지 않았습니다.
