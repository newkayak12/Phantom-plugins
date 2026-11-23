# Phantom Key-Value Console 변경 이력

## 미배포

- Redis 도구 창 아이콘을 데이터베이스와 헷갈리던 원통 대신 번개(인메모리 캐시)로 바꿨다. (#112)

## 0.1.3 (2026-11-22)

- 콘솔에 입력한 FLUSHDB·FLUSHALL은 대상 서버와 삭제 범위를 보여 주고 확인을 받은 뒤에만 보낸다. 취소하면 아무것도 보내지 않는다. (#64)
- 콘솔에 입력한 비밀번호(AUTH, HELLO AUTH, MIGRATE AUTH/AUTH2, ACL SETUSER, CONFIG SET requirepass/masterauth)를 출력에서 `***`로 가리고 히스토리에 남기지 않는다. (#64)
- 키 목록이 키마다 TYPE·TTL을 따로 보내던 것(최대 4,000번 왕복)을 500개씩 묶어 파이프라인으로 보낸다. (#64)
