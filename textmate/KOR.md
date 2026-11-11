# TextMate Bundles

`org.jetbrains.plugins.textmate` · [English](./README.md)

다른 플러그인이 다루지 않는 파일 형식에 TextMate와 VS Code 문법으로 하이라이팅, 괄호 짝, 주석, 스니펫을 제공합니다. 기본 문법 64개(`lib/bundles`)와 Settings | Editor | TextMate Bundles에서 추가한 문법을 씁니다. Markdown이 코드 블록 하이라이팅에 이것을 씁니다.

IntelliJ Community의 TextMate Bundles 플러그인(`plugins/textmate`, Apache 2.0)을 여기서 빌드해, Phantom을 새로 빌드하지 않아도 업데이트됩니다. 플러그인 id와 콘텐츠
모듈 이름은 IntelliJ 것을 그대로 써서 이것에 의존하는 플러그인과 번들 모듈이 그대로 동작합니다. 버전(`262.261111.x`)이 번들 빌드보다 높아,
설치하면 Phantom에 아직 번들된 사본을 대신합니다.

문법은 IDE의 `plugins/textmate-plugin`만이 아니라, 이 플러그인이 설치된 폴더에서 읽습니다.
