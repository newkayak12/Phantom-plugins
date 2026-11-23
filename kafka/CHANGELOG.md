# Phantom for Kafka 변경 이력

## 미배포

- Kafka 도구 창 아이콘을 문서처럼 보이던 줄 세 개 대신 칸으로 나뉜 파티션 로그 세 줄로 바꿨다.

## 0.1.6 (2026-11-22)

- 이름을 바꿨다: Kafka Support → Phantom for Kafka. Phantom으로 시작하고, 지원하는 프로젝트 이름은 뒤에 둔다. 플러그인 id와 기능은 그대로다.

## 0.1.5 (2026-11-22)

- 토픽 A를 읽는 중에 토픽 B를 고르면, 늦게 끝난 A의 메시지나 파티션 정보가 B의 것처럼 표에 뜨던 문제를 고쳤다. 토픽 선택, 새로고침, 클러스터 전환, 창 닫기는 진행 중인 읽기를 취소하고 그 결과를 버린다.
- 8초 안에 끝까지 읽지 못한 파티션이 있으면 "No messages" / "N messages" 대신 부분 결과임을 표시한다. 예: `N messages (partial: 3 partitions not finished within 8 s)`, `No messages read yet: 3 partitions not finished within 8 s`.

## 0.1.4 (2026-11-22)

- 토픽 생성창의 복제 계수 기본값을 1에서 빈칸(클러스터의 default.replication.factor 사용)으로 바꿨다. 1을 직접 입력하면 단일 복제본이라 브로커 장애 시 데이터를 잃는다고 확인받는다. (#66)
