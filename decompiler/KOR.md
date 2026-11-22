# Phantom Bytecode Decompiler for Java

`dev.phantom.decompiler` · [English](./README.md)

소스가 없는 `.class` 파일을 열면 스텁 대신 Fernflower가 디컴파일한 Java 소스를 보여 줍니다. 디버거는 그 줄을 바이트코드에 다시 맞추고, View | Show Decompiled Class로 어디서든 열 수 있습니다. Kotlin의 바이트코드 디컴파일러가 이것을 씁니다.

IntelliJ Community의 Java Bytecode Decompiler 플러그인(`plugins/java-decompiler`, Apache 2.0)을 바탕으로 Phantom의 id로 만든 플러그인입니다. `org.jetbrains.java.decompiler`는 별칭으로
남겨 두고 콘텐츠 모듈 이름도 IntelliJ 것을 그대로 써서, 이것에 의존하는 플러그인과 번들 모듈이 그대로 동작합니다. Phantom은
IntelliJ 플러그인 대신 이 빌드를 번들합니다.

엔진 테스트는 javac가 없는 IDE 런타임에서 돌아서, 왕복 테스트는 ECJ로만 컴파일합니다.

**0.1.2**: 플러그인 jar에 Fernflower의 NOTICE(`META-INF/NOTICE-decompiler`)가 들어갑니다.

**0.1.1**: 처음 클래스 파일을 디컴파일하기 전에 나오는 안내가 "Fernflower decompiler"라는 제목의 단순한 경고가 됐습니다.
디컴파일한 코드도 보호받을 수 있으니 디컴파일하는 대상의 라이선스를 확인하라는 내용입니다.
