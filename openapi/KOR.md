# Support for OpenAPI
`dev.phantom.openapi` · [English](./README.md)

YAML과 JSON으로 쓴 OpenAPI 3, Swagger 2 스펙을 지원합니다.

- **`$ref`:** `#/components/...` 항목과 다른 파일(`common.yaml#/components/schemas/Error`)로 Ctrl+B 이동, `#/components/<종류>/<이름>` 자동완성, 가리키는 대상이 없는 `$ref` 오류를 제공합니다. 스펙이 가리키는 다른 파일(나눠 둔 스펙) 안의 `$ref`도 같습니다. 스키마나 파일 이름을 바꾸면 `$ref`의 마지막 부분만 바뀝니다. 원격 참조는 검사하지 않습니다.
- **스펙 ↔ 코드:** 각 operation(경로 아래 `get:`)의 거터 아이콘으로 핸들러로 가고, 핸들러의 아이콘으로 스펙으로 돌아옵니다. 핸들러는 Phantom Endpoints 창이 아는 프레임워크(Spring MVC 등)에서 가져옵니다. 경로는 파라미터 이름을 무시하고, 파라미터 없는 기본 경로(`/api`)가 앞에 붙어도 맞춥니다. 경로가 그대로 같은 쪽이 있으면 그쪽을 우선합니다.

최상위에 `openapi:`나 `swagger:`가 있는 파일만 스펙으로 다룹니다.

아직 없는 것: 스펙 자체의 스키마 검증, 코드 생성.

## 고스트 모드

Phantom의 고스트 모드(레지스트리 키 `phantom.ghost`)가 켜져 있으면 `$ref`·스펙 모양 검사와 스펙 operation과 핸들러 사이 거터 아이콘이 꺼진다. `$ref` 이동, 완성, 이름 바꾸기는 그대로다.
