# Phantom Terminal

`dev.phantom.terminal` · [English](./README.md)

IDE 안의 터미널(View | Tool Windows | Terminal): Bash, Zsh, Fish, PowerShell, cmd 세션을 탭, 분할 창, 에디터 탭에서 쓰고, 셸 통합(명령 블록, 프롬프트, 작업 디렉터리 추적), 번들된 명령 스펙으로 하는 명령 자동완성, 누르면 열리는 파일 링크, IDE에서 열리는 명령을 지원합니다. Shell Script, Run Anything의 터미널 명령 등이 이 플러그인을 씁니다.

IntelliJ Community의 Terminal 플러그인(`plugins/terminal`, Apache 2.0)을 바탕으로 Phantom의 id로 만든 플러그인입니다. `org.jetbrains.plugins.terminal`은 별칭으로
남겨 두고 콘텐츠 모듈 이름도 IntelliJ 것을 그대로 써서, 이것에 의존하는 플러그인과 번들 모듈이 그대로 동작합니다. Phantom은
IntelliJ 플러그인 대신 이 빌드를 번들합니다.

backend, frontend, cloud, Shell Script 모듈은 번들 플러그인처럼 각자 클래스로더를 씁니다. backend의 서비스는 원격 API가 아니라
일반 애플리케이션 서비스입니다(Phantom은 frontend와 backend를 한 프로세스에서 돌립니다). 사용 통계는 수집하지 않고, Agent Workbench
홍보도 없습니다.

명령 자동완성은 [Fig 자동완성 스펙](https://github.com/withfig/autocomplete)(MIT, `terminal/LICENSE`)에서 옵니다.
`tools/fig-specs`가 고정한 버전의 스펙을 `src/main/resources/completionSpecs`의 JSON으로 바꾸고(명령 720개, git, docker,
npm, kubectl, aws, az, gcloud 포함), 이 JSON을 커밋해 두어 빌드에 Node가 필요 없습니다. JSON에는 Fig가 정적으로 적은 것만
담깁니다: 하위 명령, 옵션, 인자, 고정 값, 파일·폴더. 명령을 실행해 제안을 만드는 Fig 생성기(git 브랜치, docker 컨테이너,
npm 스크립트)는 빠집니다. 플러그인 자체 생성기(`cd`, `make` 타깃)와 다른 플러그인의 생성기는 그대로 동작합니다.

**0.2.0**: 명령 스펙을 라이선스가 없는 JetBrains 자동완성 라이브러리(`completion-spec`, `completion-db-with-extensions`)
대신 공개된 Fig 스펙에서 생성합니다. 라이브러리의 클래스는 플러그인 자체 클래스(`dev.phantom.terminal.completion.spec`)로
바꿨습니다. Shell Script 자동완성의 머신러닝 정렬(`completion-ranking-sh`)도 없어져, 제안은 기본 순서로 나옵니다. 스펙에서
숨김으로 표시된 하위 명령과 옵션은 이제 제안하지 않고 인식만 합니다.
