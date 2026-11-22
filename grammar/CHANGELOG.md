# Phantom Grammar 변경 이력

## 0.2.2

- 영어 사전의 저작권 고지를 `META-INF/NOTICE-grammar`로 넣는다.

- 쓰지 않는 gRPC 라이브러리 모듈(`intellij.libraries.grpc`)에 대한 의존을 뺀다. gRPC가 없는 Phantom에서도 문법 검사가 로드된다 (#32).
