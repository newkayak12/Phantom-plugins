# Support for JUnit
`dev.phantom.junit` · [English](./README.md)

Java용 JUnit 3, 4, 5, 6: 클래스, 메서드, 패키지, 디렉터리, 패턴, 태그, 카테고리, unique id 단위 실행 구성(거터나 컨텍스트에서
실행), 테스트 트리, 실패한 테스트 다시 실행, 모듈별·클래스별 포크 실행과 디버깅, JUnit 테스트 생성과 테스트와 대상 사이 이동,
`@MethodSource`·`@FieldSource`·`@EnumSource` 같은 어노테이션에서 대상으로 가는 참조, 잘못 선언된 테스트, JUnit 4에서 JUnit 5로
옮기기, assertion 변환 같은 인스펙션과 빠른 수정.

IntelliJ Community의 JUnit 플러그인(`plugins/junit`, `plugins/junit_rt`, `plugins/junit5_rt`, `plugins/junit6_rt`, Apache 2.0)을
바탕으로 Phantom의 id로 만든 플러그인입니다. `JUnit`은 별칭으로 남겨 두어서, 이것에 의존하는 플러그인(Code Coverage, Groovy,
Kotlin)이 그대로 동작합니다. Phantom은 IntelliJ 플러그인 대신 이 빌드를 번들합니다.
