# Phantom Grammar 변경 이력

## 미배포

- 주석과 문자열이 많은 파일에서 문법 검사가 빨라진다. 언어 감지가 텍스트마다 파일 전체의 감지 결과 표를 새로 만들던 것을 한 번만 만든다. (#121)

## 0.2.3

- 마켓플레이스 설치에서 설정 페이지, 알림, 인스펙션, 사전 이름이 "Grazie" 대신 중립적인 이름(Grammar and Style, Proofreader, Extended Dictionary)으로 나온다. 설정 내보내기와 알림 설정에 보이는 이름도 Proofreading Settings, Proofreading notifications로 바꿨다.
- Grazie 로고 아이콘(`icons/Grazie.svg`, `icons/stroke/Grazie*.svg`)을 플러그인 아이콘과 같은 모양의 자체 아이콘으로 바꿨다.
- NOTICE가 언어 번들에 든 GPL 2.0/3.0, LGPL, MPL 사전을 밝히고, 서면 제공 약속이 이 구성요소까지 포함한다. 소스 신청 창구는 공개 저장소(newkayak12/Phantom)의 이슈다.
- 릴리스에 english-pos-dict 0.6의 소스로 컴파일된 jar 대신 0.6을 만든 커밋의 업스트림 스냅숏(단어 목록과 빌드 스크립트)을 첨부한다.

## 0.2.2

- 영어 사전의 저작권 고지를 `META-INF/NOTICE-grammar`로 넣는다.

- 쓰지 않는 gRPC 라이브러리 모듈(`intellij.libraries.grpc`)에 대한 의존을 뺀다. gRPC가 없는 Phantom에서도 문법 검사가 로드된다 (#32).
