# Phantom TestNG

`dev.phantom.testng` · [English](./README.md)

Java용 TestNG: 클래스, 메서드, 패키지, 그룹, 패턴, suite 파일 단위 실행 구성(거터나 컨텍스트에서 실행), 테스트 트리, 다시 실행,
모듈별 포크 실행과 디버깅, TestNG 테스트 생성과 파일 템플릿, `dataProvider`·`dependsOnMethods`·`groups`·`testng.xml`에서 대상으로
가는 참조, JUnit 테스트를 TestNG로 바꾸는 것 같은 인스펙션과 빠른 수정. TestNG 자체(`testng.jar`)도 플러그인에
들어 있어서, 아직 TestNG가 없는 모듈에서 변환하면 그 모듈에 추가해 줍니다.

IntelliJ Community의 TestNG 플러그인(`plugins/testng`, `plugins/testng_rt`, Apache 2.0)을 바탕으로 Phantom의 id로 만든 플러그인입니다.
`TestNG-J`는 별칭으로 남겨 두고 콘텐츠 모듈 이름도 IntelliJ 것을 그대로 써서, 이것에 의존하는 플러그인과 번들 모듈이 그대로
동작합니다. Phantom은 IntelliJ 플러그인 대신 이 빌드를 번들합니다.
