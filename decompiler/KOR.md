# Java Bytecode Decompiler

`org.jetbrains.java.decompiler` · [English](./README.md)

소스가 없는 `.class` 파일을 열면 스텁 대신 Fernflower가 디컴파일한 Java 소스를 보여 줍니다. 디버거는 그 줄을 바이트코드에 다시 맞추고, View | Show Decompiled Class로 어디서든 열 수 있습니다. Kotlin의 바이트코드 디컴파일러가 이것을 씁니다.

IntelliJ Community의 Java Bytecode Decompiler 플러그인(`plugins/java-decompiler`, Apache 2.0)을 여기서 빌드해, Phantom을 새로 빌드하지 않아도 업데이트됩니다. 플러그인 id와 콘텐츠
모듈 이름은 IntelliJ 것을 그대로 써서 이것에 의존하는 플러그인과 번들 모듈이 그대로 동작합니다. 버전(`262.261111.x`)이 번들 빌드보다 높아,
설치하면 Phantom에 아직 번들된 사본을 대신합니다.

엔진 테스트는 javac가 없는 IDE 런타임에서 돌아서, 왕복 테스트는 ECJ로만 컴파일합니다.
