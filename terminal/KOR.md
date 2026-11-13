# Phantom Terminal

`dev.phantom.terminal` · [English](./README.md)

IDE 안의 터미널(View | Tool Windows | Terminal): Bash, Zsh, Fish, PowerShell, cmd 세션을 탭, 분할 창, 에디터 탭에서 쓰고, 셸 통합(명령 블록, 프롬프트, 작업 디렉터리 추적), 번들된 명령 스펙으로 하는 명령 자동완성, 누르면 열리는 파일 링크, IDE에서 열리는 명령을 지원합니다. Shell Script, Run Anything의 터미널 명령 등이 이 플러그인을 씁니다.

IntelliJ Community의 Terminal 플러그인(`plugins/terminal`, Apache 2.0)을 바탕으로 Phantom의 id로 만든 플러그인입니다. `org.jetbrains.plugins.terminal`은 별칭으로
남겨 두고 콘텐츠 모듈 이름도 IntelliJ 것을 그대로 써서, 이것에 의존하는 플러그인과 번들 모듈이 그대로 동작합니다. Phantom은
IntelliJ 플러그인 대신 이 빌드를 번들합니다.

backend, frontend, cloud, Shell Script 모듈은 번들 플러그인처럼 각자 클래스로더를 씁니다. backend의 서비스는 원격 API가 아니라
일반 애플리케이션 서비스입니다(Phantom은 frontend와 backend를 한 프로세스에서 돌립니다). 사용 통계는 수집하지 않고, Agent Workbench
홍보도 없습니다.
