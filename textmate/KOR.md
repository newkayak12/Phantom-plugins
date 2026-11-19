# TextMate Bundles Support

`dev.phantom.textmate` · [English](./README.md)

다른 플러그인이 다루지 않는 파일 형식에 TextMate와 VS Code 문법으로 하이라이팅, 괄호 짝, 주석, 스니펫을 제공합니다. 기본 문법 64개(`lib/bundles`)와 Settings | Editor | TextMate Bundles에서 추가한 문법을 씁니다. Markdown이 코드 블록 하이라이팅에 이것을 씁니다.

IntelliJ Community의 TextMate Bundles 플러그인(`plugins/textmate`, Apache 2.0)을 바탕으로 Phantom의 id로 만든 플러그인입니다. `org.jetbrains.plugins.textmate`는 별칭으로
남겨 두고 콘텐츠 모듈 이름도 IntelliJ 것을 그대로 써서, 이것에 의존하는 플러그인과 번들 모듈이 그대로 동작합니다. Phantom은
IntelliJ 플러그인 대신 이 빌드를 번들합니다.

문법은 IDE의 `plugins/textmate-plugin`만이 아니라, 이 플러그인이 설치된 폴더에서 읽습니다.

**0.1.1**: 플러그인 jar에 번들 문법의 라이선스(`META-INF/LICENSE-textmate`: VS Code의 MIT 라이선스와, 자기 라이선스를 따르는
번들 목록, HashiCorp의 HCL과 Terraform은 MPL 2.0)가 들어 있고, 포함된 라이브러리(Joni, JCodings, MIT)가
`META-INF/THIRD-PARTY-NOTICES.txt`에 나옵니다.
