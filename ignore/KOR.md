# Phantom Ignore Files

`dev.phantom.ignore` · [English](./README.md)

- `.dockerignore`, `.npmignore`, `.helmignore`, `.prettierignore`, `.eslintignore`, `.stylelintignore`, `.gcloudignore`,
  `.vscodeignore`를 `.gitignore`처럼 편집합니다. 하이라이트, 경로 자동 완성과 이동, 주석 토글, 중복 항목 검사가 됩니다.
  `.gitignore`가 플러그인 없이 쓰는 플랫폼 ignore 언어를 그대로 씁니다.
- 디렉터리에서 **New | Ignore File from Template…**: 파일(`.gitignore` 또는 위 파일)과 템플릿(Java, Kotlin, Gradle, Maven, IntelliJ,
  VS Code, macOS, Windows, Linux, Node, Python, Go, Rust, Docker)을 고르면, 템플릿마다 `### 이름 ###` 머리글 아래에 덧붙입니다.
  파일에 이미 있는 줄은 빼고 넣습니다. 템플릿은 [github/gitignore](https://github.com/github/gitignore)(CC0)에서 가져와 다듬었습니다.

.ignore 플러그인을 대체합니다. Phantom은 둘을 함께 로드하지 않습니다.
