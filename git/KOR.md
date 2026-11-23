# Phantom for Git

`dev.phantom.git` · [English](./README.md)

IDE 안의 Git: 클론, 커밋(GPG 서명 포함), 푸시, 풀, 페치, 병합, 리베이스와 대화형 리베이스, 체리픽, 되돌리기와 리셋, 브랜치와
브랜치 위젯, Git 로그와 파일 이력, 어노테이션, 스태시와 셸프, 스테이징 영역, 충돌 해결, 워크트리, 그리고 Version Control | Git의
Git 설정. Phantom for GitHub, Modal Commit 등 다른 플러그인이 이것을 바탕으로 동작합니다.

IntelliJ Community의 Git 플러그인(`plugins/git4idea`, Apache 2.0)을 바탕으로 Phantom의 id로 만든 플러그인입니다. `Git4Idea`는
별칭으로 남겨 두고 콘텐츠 모듈 이름도 IntelliJ 것을 그대로 써서, 이것에 의존하는 플러그인과 번들 모듈이 그대로 동작합니다.
Phantom은 IntelliJ 플러그인 대신 이 빌드를 번들합니다.

shared, backend, 로컬 히스토리 모듈은 플러그인과 함께 로드되고, frontend, terminal, coverage 모듈은 번들 플러그인처럼 자기
클래스 로더를 따로 씁니다. backend가 frontend에 제공하는 서비스는 원격 API가 아니라 일반 애플리케이션 서비스입니다(Phantom은
frontend와 backend를 한 프로세스에서 실행). 사용 통계는 수집하지 않습니다. 메모리 내 리베이스는
[git-revise](https://github.com/mystor/git-revise)(MIT, jar의 `licenses/NOTICE`)에서 가져온 코드입니다.

Backup and Sync 기록(도구 창 "Backup and Sync History"와 기록 보기 동작)도 선택 모듈 `phantom.git.settingsSync`로 여기에 있습니다.
그래서 IDE 본체는 Git 없이 컴파일됩니다. Phantom 262.261123 이상이 필요합니다. 그보다 이전 빌드는 본체에 이 화면이 아직 있습니다.
