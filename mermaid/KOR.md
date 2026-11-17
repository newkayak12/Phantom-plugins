# Mermaid Support

`com.phantom.mermaid` · [English](./README.md)

`.mmd`, `.mermaid` 파일과 Markdown의 ` ```mermaid ` 블록에서 [Mermaid](https://mermaid.js.org/)를 편집하고 미리 봅니다.
하이라이트, 자동 완성, 포맷, 접기, 노드 id 이름 바꾸기와 사용처 찾기, 검사, 에디터 옆 미리보기를 제공합니다.

IntelliJ Community의 Mermaid 플러그인(`plugins/mermaid`, Apache 2.0)을 자체 id와 패키지(`com.phantom.mermaid`)로
빌드한 것입니다. Phantom 빌드 없이 업데이트되고, Phantom은 JetBrains 사본을 번들하지 않아도 됩니다.
두 플러그인이 같은 Mermaid 언어와 파일 형식을 등록하므로 함께 켤 수 없습니다. JetBrains 사본(`com.intellij.mermaid`)이
켜져 있으면 이 플러그인은 로드되지 않고 IDE가 그 사실을 알립니다. Settings → Plugins에서 Mermaid(JetBrains)를 끄고
다시 시작하세요.

## JetBrains 사본보다 빠른 점

- **미리보기는 마지막 텍스트만 그립니다.** 다이어그램을 그리는 동안 들어온 편집은 하나로 합쳐서, 빠르게 15번 고치면
  15번을 차례로 그리지 않고 2번 그립니다. 전에는 큰 다이어그램에서 미리보기가 타이핑을 따라가지 못했습니다.
- **미리보기는 타이핑이 멈춘 뒤에 그립니다**(20ms → 150ms). 키를 누를 때마다 파일 전체를 복사하지 않고, 멈춘 뒤 한 번
  읽습니다. 바뀌지 않은 텍스트(되돌리기, 저장)는 다시 그리지 않습니다.
- **Markdown에서 바뀌지 않은 다이어그램은 다시 그리지 않습니다.** Markdown 미리보기는 편집할 때마다 블록을 새로
  만드는데, 캐시가 블록 기준이어서 키를 누를 때마다 파일의 모든 다이어그램을 다시 그렸고, 교체된 블록이 SVG와 함께
  메모리에 남았습니다. 이제 내용과 테마 기준으로 캐시합니다(최근 64개).
- **mermaid.js(5MB)를 한 번만 읽습니다.** 미리보기를 열 때마다 읽지 않고, 브라우저에 이미 있으면 읽기 전에 응답합니다.
  IDE 빌드 날짜가 아니라 플러그인 날짜로 표시해서, 업데이트 뒤에도 미리보기가 예전 mermaid.js를 쓰지 않습니다.
  테마 파일은 브라우저 캐시를 쓰지 않습니다.

## mermaid.js 빌드

`src/main/resources/com/phantom/mermaid/markdown/jcef/mermaid.js`는 mermaid 11.15.0에 플러그인의 브라우저 쪽 코드
(`browser-build/browser/extension`, Kotlin/JS)를 합친 것입니다. 그 코드를 고친 뒤에는 JDK 17로 다시 빌드합니다.

```
cd browser-build && ./updateMermaidJs.sh
```

## 테스트

업스트림 테스트(421개, 문서 예제를 추출해야 하는 `OfficialExamplesParsingTest` 제외)와, 두 미리보기가 다이어그램을
몇 번 그리는지 세는 `PreviewRenderingTest`입니다. 미리보기 테스트는 JCEF를 쓰며 `CI`가 설정된 곳에서는 건너뜁니다.
