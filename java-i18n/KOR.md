# Phantom Internationalization for Java

`dev.phantom.java-i18n` · [English](./README.md)

Java 국제화: 하드코딩된 문자열, 찾을 수 없는 프로퍼티 키, 맞지 않는 메시지 포맷 인자 개수, 서로 어긋난 리소스 번들, 중복 문자열
리터럴, 대화상자 제목 대소문자, 지원되지 않거나 Basic Latin이 아닌 문자, 의심스러운 로케일을 찾는 인스펙션, 문자열 리터럴을
프로퍼티 파일로 옮기는 I18nize, `getMessage("key")` 호출을 메시지 내용으로 접어 보여 주기와 Edit Property Value, Java 코드와 프로퍼티
키 사이의 참조·사용처 찾기·이름 바꾸기·인라인.

IntelliJ Community의 Java Internationalization 플러그인(`plugins/java-i18n`, Apache 2.0)을 바탕으로 Phantom의 id로 만든 플러그인입니다.
`com.intellij.java-i18n`은 별칭으로 남겨 두고 확장 지점도 `com.intellij.java-i18n.*` 이름을 그대로 써서, 이것에 의존하는 플러그인이
그대로 동작합니다. Phantom은 IntelliJ 플러그인 대신 이 빌드를 번들합니다.
