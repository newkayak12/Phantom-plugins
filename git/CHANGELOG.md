# Support for Git 변경 이력

## 미배포

- 표시 이름을 바꿨다: Phantom for Git → Support for Git. 플러그인 id와 기능은 그대로다. (#?)

## 0.2.2 (2026-11-23)

- Backup and Sync 기록 동작의 id를 `phantom.settingsSync.history`로 바꿨다. IntelliJ의 `settingsSync.history`와 겹쳐 테스트용 IDE에서 Git 테스트 대부분이 실패했다.

## 0.2.1 (2026-11-23)

- 이름을 바꿨다: Git Support → Phantom for Git. Phantom으로 시작하고, 지원하는 프로젝트 이름은 뒤에 둔다. 플러그인 id와 기능은 그대로다.

## 0.2.0 (2026-11-23)

- Backup and Sync 기록(도구 창 "Backup and Sync History"와 기록 보기 동작)을 IDE 본체의 settings-sync-core에서 옮겨 와 선택 모듈 `phantom.git.settingsSync`로 넣었다. 본체가 Git 플러그인 없이 컴파일된다. Backup and Sync가 없으면 이 모듈은 꺼진다.
- Phantom 262.261123 이상에서만 설치된다. 그보다 이전 빌드는 본체에 같은 기록 화면이 있어 겹친다.

## 0.1.0 (2026-11-18)

- IntelliJ idea/262.10968.63의 Git 플러그인(`plugins/git4idea`)을 Phantom의 id `dev.phantom.git`로 옮겼다. `Git4Idea`는 별칭으로 남아 GitHub Support 등 Git에 의존하는 플러그인이 그대로 동작한다. (#28)
- 사용 통계를 보내지 않는다. (#28)
- Windows에서 새 Git 버전 확인이 JetBrains의 Git 목록을 스스로 받지 않는다(`-Dphantom.jetbrains.services=true`일 때만). Git이 없을 때의 "Download and Install"은 그대로다. (#28)
- WSL 1 오류가 JetBrains 이슈 트래커 대신 WSL 2로 바꾸는 방법을 안내한다. (#28)
- Performance Testing 연동이 없다(Phantom은 Performance Testing 플러그인을 번들하지 않는다). (#28)
