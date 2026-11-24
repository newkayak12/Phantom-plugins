# Support for TextMate Bundles
`dev.phantom.textmate` · [English](./README.md)

다른 플러그인이 다루지 않는 파일 형식에 TextMate와 VS Code 문법으로 하이라이팅, 괄호 짝, 주석, 스니펫을 제공합니다. 기본 문법 22개(`lib/bundles`)와 Settings | Editor | TextMate Bundles에서 추가한 문법을 씁니다. Markdown이 코드 블록 하이라이팅에 이것을 씁니다.

IntelliJ Community의 TextMate Bundles 플러그인(`plugins/textmate`, Apache 2.0)을 바탕으로 Phantom의 id로 만든 플러그인입니다. `org.jetbrains.plugins.textmate`는 별칭으로
남겨 두고 콘텐츠 모듈 이름도 IntelliJ 것을 그대로 써서, 이것에 의존하는 플러그인과 번들 모듈이 그대로 동작합니다. Phantom은
IntelliJ 플러그인 대신 이 빌드를 번들합니다.

문법은 IDE의 `plugins/textmate-plugin`만이 아니라, 이 플러그인이 설치된 폴더에서 읽습니다.

**0.1.4**: 이름을 바꿨습니다: TextMate Bundles Support → Phantom for TextMate Bundles. Phantom으로 시작하고 지원하는 프로젝트 이름을 뒤에 둡니다. 플러그인 id와 기능은 그대로입니다.

**0.1.1**: 플러그인 jar에 번들 문법의 라이선스(`META-INF/LICENSE-textmate`: VS Code의 MIT 라이선스와, 자기 라이선스를 따르는
번들 목록, HashiCorp의 HCL과 Terraform은 MPL 2.0)가 들어 있고, 포함된 라이브러리(Joni, JCodings, MIT)가
`META-INF/THIRD-PARTY-NOTICES.txt`에 나옵니다.

**0.1.2**: 문법 64개 중 22개만 넣습니다. Kotlin, SQL, Dockerfile, .env, INI, 로그, Makefile, Batch, PowerShell, diff, git 파일,
JSP, AsciiDoc, HCL, Terraform, Bazel, CMake, JavaScript, TypeScript, CSS, SCSS, Less입니다. IDE는 프로젝트를 열 때
넣어 둔 문법을 모두 읽습니다. 뺀 것은 Phantom 플러그인이 다루거나(Java, Groovy, JSON, YAML, Markdown, 셸, XML, HTML)
Java·Kotlin 백엔드 프로젝트에서 쓰지 않는 언어입니다. 필요하면 Settings | Editor | TextMate Bundles에서 추가할 수 있습니다.

**0.1.3**: VS Code가 다른 프로젝트에서 가져온 문법은 넣은 문법마다 그 프로젝트의 라이선스를 폴더에 함께 담습니다
(`lib/bundles/<이름>/LICENSE`, Docker는 `NOTICE`도). Batch(Michael Mims, MIT), SQL·CSS·JavaScript·TypeScript(Microsoft, MIT),
Dockerfile(Docker, Apache 2.0), .env, 로그, Makefile, PowerShell, git 파일, SCSS, Less, 그리고 INI·diff·JavaScript 정규식의
TextMate 번들 라이선스입니다.
