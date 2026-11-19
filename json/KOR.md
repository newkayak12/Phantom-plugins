# Phantom JSON

`dev.phantom.json` · [English](./README.md)

JSON, JSON5, JSON Lines 파일: 하이라이팅, 포매팅, 접기, 구조 보기, 브레드크럼, JSON Pointer 복사, 그리고 JSON Schema 매핑, schemastore.org 카탈로그, 자동완성, 검증(networknt 검증기 사용), 문서, 빠른 수정. Phantom의 yaml, toml, markdown, openapi, github, grazie, mermaid 플러그인이 이것을 바탕으로 동작합니다.

IntelliJ Community의 JSON 플러그인(`json`, Apache 2.0)을 바탕으로 Phantom의 id로 만든 플러그인입니다. `com.intellij.modules.json`은 별칭으로
남겨 두고 콘텐츠 모듈 이름도 IntelliJ 것을 그대로 써서, 이것에 의존하는 플러그인과 번들 모듈이 그대로 동작합니다. Phantom은
IntelliJ 플러그인 대신 이 빌드를 번들합니다.

**0.1.1**: 플러그인 jar의 `META-INF/THIRD-PARTY-NOTICES.txt`에 포함된 라이브러리(json-schema-validator, Apache 2.0; Joni와 JCodings,
MIT) 목록이 있고, `META-INF/licenses/`에 MIT 전문이 들어 있습니다.
