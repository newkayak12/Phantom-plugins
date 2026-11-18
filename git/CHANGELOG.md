# Git Support 변경 이력

## 미배포

## 0.1.0 (2026-11-18)

- IntelliJ idea/262.10968.63의 Git 플러그인(`plugins/git4idea`)을 Phantom의 id `dev.phantom.git`로 옮겼다. `Git4Idea`는 별칭으로 남아 GitHub Support 등 Git에 의존하는 플러그인이 그대로 동작한다. (#28)
- 사용 통계를 보내지 않는다. (#28)
- Windows에서 새 Git 버전 확인이 JetBrains의 Git 목록을 스스로 받지 않는다(`-Dphantom.jetbrains.services=true`일 때만). Git이 없을 때의 "Download and Install"은 그대로다. (#28)
- WSL 1 오류가 JetBrains 이슈 트래커 대신 WSL 2로 바꾸는 방법을 안내한다. (#28)
- Performance Testing 연동이 없다(Phantom은 Performance Testing 플러그인을 번들하지 않는다). (#28)
