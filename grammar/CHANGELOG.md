# Phantom Grammar 변경 이력

## 미배포

- 쓰지 않는 gRPC 라이브러리 모듈(`intellij.libraries.grpc`)에 대한 의존을 뺀다. gRPC가 없는 Phantom에서도 문법 검사가 로드된다 (#32).
