# Phantom Key-Value Console

`dev.phantom.redis` · [English](./README.md)

Redis 호환 서버(Redis, Valkey, KeyDB…)용 키 브라우저와 콘솔로, Redis 도구 창에 있습니다. SCAN과 패턴으로 키를 찾고(KEYS *는
쓰지 않습니다), 모든 타입의 값(JSON을 들여 쓴 문자열, 해시, 리스트, 셋, 정렬된 셋, 스트림)을 보고 지우며, redis-cli 형식의
출력과 기록이 있는 명령줄을 씁니다. Redis 프로토콜(RESP)을 직접 말하고 비밀번호, ACL 사용자, 데이터베이스, TLS를 지원합니다.
연결은 도구 창이 보이는 동안에만 열립니다. 비밀번호는 IDE 비밀번호 저장소에 "Phantom Redis"라는 서비스 이름으로 저장됩니다.

제한: TLS는 서버 인증서가 호스트 이름과 맞는지 확인합니다. 키 목록은 최대 5초 또는 SCAN 1,000번까지 찾고, 문자열은 앞 1 MiB만,
콘솔 응답은 최대 1,000줄만 보여 줍니다. 콘솔은 SELECT(데이터베이스는 Edit Server…에서 바꿉니다)와 SUBSCRIBE, BLPOP 같은
블로킹 명령을 보내지 않습니다.

Redis는 Redis Ltd.의 상표입니다. 이 플러그인은 Redis Ltd., Valkey 프로젝트, KeyDB 프로젝트와 관계가 없고 그들의 보증을
받지 않았습니다.

**0.1.2**: 이름이 "Redis Support"에서 "Phantom Key-Value Console"로 바뀌었습니다. 플러그인 id(`dev.phantom.redis`), 저장된
서버와 비밀번호는 그대로입니다.
