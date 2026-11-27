# Support for Kubernetes
`dev.phantom.kubernetes` · [English](./README.md)

Helm 차트 지원입니다. 차트 템플릿(`<chart>/Chart.yaml` 옆의 `<chart>/templates/…`) 안에서만 동작합니다.

- **자동완성:** 차트의 `values.yaml`에서 `.Values.`와 `$.Values.` 경로를 한 단계씩, 기본값과 함께 보여 줍니다. `.Release.`, `.Chart.`, `.Capabilities.` 필드와 `include "…"`, `template "…"`의 템플릿 이름도 채웁니다. `.`을 입력하면 목록이 뜹니다.
- **이동:** `.Values` 경로에서 Ctrl+B를 누르면 `values.yaml`의 키로, `include` 이름에서 누르면 `define`으로 갑니다.
- **없는 값 (약한 경고):** `.Values.imgae.tag`처럼 `values.yaml`에 없는 경로를 알려 줍니다. `.Values.global` 아래와, 기본값이 `{}`나 null인 키 아래는 알리지 않습니다. 서브차트 이름 아래 경로는 그 서브차트의 `values.yaml`에서, 서브차트의 경로는 부모 `values.yaml`의 서브차트 이름 아래에서도 찾고, `{{/* … */}}` 주석 안의 경로는 검사하지 않습니다.
- `_helpers.tpl`을 텍스트로 열어서 그 안의 `define`도 찾습니다.

프로젝트에 `Chart.yaml`이 있으면 Phantom이 이 플러그인 설치를 제안합니다.

Kubernetes 매니페스트(스키마 자동완성, 클러스터 보기)는 Marketplace의 **Kubernetes by Red Hat**을 설치하세요. Phantom에서 그대로 동작합니다.

아직 없는 것: Go 템플릿 언어(`{{ }}` 하이라이팅), `values.schema.json`, 차트 렌더링.
