# Phantom Bytecode Viewer

`dev.phantom.bytecode` · [English](./README.md)

컴파일된 Java·Kotlin 클래스의 바이트코드를 ASM 형식으로 보여 줍니다. `.java`, `.kt`, `.class` 파일을 선택하거나 클래스 안에 캐럿을 두고
**View | Show Bytecode**를 엽니다. Bytecode 도구 창은 에디터를 따라갑니다. 소스의 캐럿이나 선택 영역에 맞는 명령어를 표시하고(Sync with Editor),
Show Debug Info로 줄 번호와 지역 변수를 켜고 끕니다. 소스 파일은 먼저 컴파일해야 하며, 클래스 파일이 없으면 빌드를 제안합니다.

IntelliJ Community의 Bytecode Viewer(`plugins/ByteCodeViewer`, Apache 2.0)를 id, 설정, 확장 지점만 바꿔 옮겨 왔습니다.
그래서 Phantom을 새로 빌드하지 않아도 업데이트됩니다. Phantom에 번들 Bytecode Viewer가 아직 있다면 그쪽은 꺼 주세요.
