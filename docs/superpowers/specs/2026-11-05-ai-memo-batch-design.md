# AI 메모: 에디터 줄/범위에 명령을 달고 한 번에 세션으로 보내기 (설계)

플러그인: `ai-integrations` (Phantom AI Integration, `dev.phantom.ai`)
목업: [mock.png](./2026-11-05-ai-memo-batch/mock.png)

## 왜

코드를 읽다 보면 여기저기 고칠 곳이 보인다. 지금은 위치를 기억해 두었다가 경로, 줄, 코드를 직접 복사해서
에이전트 터미널에 한 건씩 적어야 한다. PowerPoint 메모나 IDE에서 GitHub PR을 리뷰할 때처럼 **읽으면서 그 자리에
명령을 달아 두고, 다 읽은 뒤 한 번에 보내고 싶다.**

핵심 두 가지 (사용자가 직접 말함):
1. 에디터에서 **한 줄 또는 여러 줄 선택 범위**를 잡고 거기에 명령을 적는다.
2. 쌓인 명령을 코드와 함께 **한 번에 일괄로** 에이전트 세션에 보낸다.

## 목표

- 에디터의 한 줄 또는 여러 줄 범위에 AI 메모(명령 텍스트)를 단다. 여러 파일에 걸쳐 쌓을 수 있다.
- 메모가 달린 범위와 메모는 **선택한 코드와 메모가 테두리 하나로 묶인 블록**으로 보인다 (사용자 피드백: "한 블록으로 묶는 게 낫다").
- 보낼 때 모든 메모를 프롬프트 하나로 합쳐 세션에 보낸다.
- 보낼 때 세션을 고른다. 기본값은 마지막에 쓴 세션이다. 열린 세션이 없으면 새 세션을 열어 보낸다.

## 하지 않는 것

- 메모 영속화. 메모는 **일회용**이다. IDE를 재시작하거나 프로젝트를 닫으면 사라진다 (사용자가 정함).
- 에이전트의 답을 메모에 되달기, 메모별 진행 상태 추적, 스레드 형태의 답글.
- JetBrains 업스트림과의 호환. Phantom은 포크이고 업스트림을 따라가지 않는다 (사용자가 직접 말함).
  그래서 `collaborationTools`의 internal/experimental API를 마음대로 써도 된다.

## 이러면 실패

- 메모를 다는 동안 코드 편집이 방해받는다 (포커스를 뺏기거나, 타이핑을 막거나, 블록이 코드를 가린다).
- 여러 메모가 섞여서 에이전트가 어떤 명령이 어떤 코드에 대한 것인지 헷갈린다.
- 한 번에 보내기가 아니라 결국 메모마다 따로 보내게 된다.

## 화면과 흐름 (목업 참고)

UI 모양은 **IDE의 GitHub/GitLab PR 리뷰(in-editor review)를 그대로 따른다** (사용자 제안).
같은 부품이 Phantom 본체의 `Contents/lib/intellij.platform.collaborationTools.jar`에 있다:

| 쓰임 | 후보 부품 (`com.intellij.collaboration.ui.codereview.*`) |
|---|---|
| 거터 `+`, 드래그로 여러 줄 잡기 | `editor.CodeReviewEditorGutterControlsRenderer`, `CodeReviewEditorGutterControlsModel`, `CodeReviewCommentableEditorModel` |
| 범위를 테두리로 묶어 한 블록으로 표시 | `editor.CodeReviewEditorInlayRangeOutlineUtils`, `comment.CommentedCodeFrameRenderer` |
| 코드 사이 인라인 블록 | `editor.CodeReviewComponentInlayRenderer`, `CodeReviewEditorInlaysModel` |
| 입력칸 | `comment.CodeReviewCommentTextFieldFactory`, `CodeReviewSubmittableTextViewModel` |
| 에디터 액션 | `editor.action.CodeReviewEditorNewCommentAction` (참고용) |

부품이 맞지 않으면 같은 모양을 직접 그려도 된다. 그 판단은 구현자에게 맡긴다. 기준은 PR 리뷰 화면과 같은 느낌이다.

1. **추가**: 줄 거터의 `+`를 클릭하거나 여러 줄을 드래그한다. 또는 선택 영역이 있는 상태에서 액션 "AI 메모 추가"를 실행한다
   (에디터 우클릭 메뉴와 단축키에 등록). 선택한 범위 바로 아래에 입력칸이 열리고, 범위와 입력칸이 한 블록으로 묶여 보인다.
   선택 없이 실행하면 커서가 있는 줄 하나가 범위다.
   - ↩ 저장 · Esc 취소 · ⌘↩(Ctrl+↩) 저장하고 바로 보내기 흐름(아래 4번) 시작. 여러 줄 입력은 ⇧↩.
2. **표시**: 저장된 메모는 노란 테두리 블록이다. 머리에는 `AI 메모 n/N · 29–33줄`과 편집/삭제 버튼, 본문에는 메모 텍스트가 있다.
   코드를 고쳐서 줄이 밀려도 메모는 그 코드를 따라간다 (RangeMarker). 범위의 코드가 전부 지워지면 메모도 지운다.
3. **목록**: AI Integration 툴 윈도우에 "AI 메모" 목록이 있다. 메모는 파일·줄 순서로 나열되고, 각 항목에 체크박스가 있다
   (기본은 전부 체크). 항목을 클릭하면 그 위치로 이동한다. 목록 아래에 "N개 한 번에 보내기" 버튼이 있다.
   메모가 하나라도 있을 때만 보인다.
4. **보내기**: 버튼(또는 ⌘↩)을 누르면 세션 선택 팝업이 뜬다.
   - 범위: worktree마다 프로젝트 창이 따로 열리므로 **메모 목록과 보내기는 창(= 그 worktree) 단위**다 (사용자가 정함).
     세션 후보는 그 worktree의 세션이고, 새 세션도 그 worktree에서 연다. 그래서 프롬프트의 경로는 항상 worktree 기준
     상대 경로다.
   - 항목: 그 worktree에 열린 세션 전부(각 항목에 상태 표시: 일하는 중 / 응답 대기 중 / 완료), "새 Claude 세션", "새 Codex 세션".
   - 기본 선택은 **마지막에 쓴 세션**이다. 열린 세션이 없으면 "새 Claude 세션"이 기본이다.
   - 고르면 체크된 메모를 프롬프트 하나로 합쳐 `TerminalIo.submit()`으로 보내고(브래킷 붙여넣기 + Enter),
     그 세션 탭을 앞으로 띄운다 (`PhantomAiApi.focusSession`). 새 세션은 `startSession`/`OpenWorktree.newSession(prompt=...)`으로 연다.
   - 보내기 직전에 메모가 달린 문서를 저장한다 (에이전트는 디스크의 파일을 읽으므로, 프롬프트의 코드와 줄이 디스크와 같아야 한다).
   - 보낸 메모는 에디터와 목록에서 지운다. 체크하지 않은 메모는 남는다.
5. **바쁜 세션 (대기열)**: 고른 세션이 일하는 중(WORKING)이거나 응답 대기 중(WAITING, 권한 요청 등)이면
   **끼어들지 않는다** (사용자가 정함). 프롬프트를 그 세션의 대기열에 넣고 사용자에게 상태를 알린다
   (예: 알림 "claude · feat-x 일하는 중: 끝나면 메모 3개를 보냅니다"). 세션이 완료(DONE)가 되면 그때 보낸다.
   - 프롬프트는 대기열에 넣는 순간 만든다 (그때 파일 저장과 코드 캡처). 메모는 에디터에서 지우고, 목록에는
     "대기 중 → claude · feat-x" 항목 하나로 남긴다. 이 항목에서 취소할 수 있고, 취소하면 메모가 에디터로 돌아온다.
     (제 판단: 큐에 넣은 뒤에 코드가 바뀌어도 맞추지 않는다.)
   - 한 세션의 대기열에 여러 개가 있으면 하나씩 보낸다. 하나를 보내고 다시 DONE이 될 때까지 기다린다.
   - 대기 중에 세션이 닫히면 보내지 않는다. 알리고 메모를 목록과 에디터로 되돌린다.
   - 상태는 hooks가 알려주는 `AgentStatus`(WORKING/WAITING/DONE)를 따른다. 상태가 없는(null, 아직 hook 없음) 새 세션은
     처음 시작할 때 `newSession(prompt=...)`로 프롬프트를 함께 넘긴다.
   - "마지막에 쓴 세션"이 바쁘더라도 기본 선택은 그대로 두고, 팝업에 상태만 보인다.

## 프롬프트 형식

```
아래 코드 위치마다 요청을 처리해줘.

1. src/main/java/app/batch/BatchProps.java:29-33
   ```java
   <범위의 코드 그대로>
   ```
   → 두 메서드 다 StringUtils.hasText 로 바꾸고 테스트 추가

2. src/main/kotlin/app/batch/SchedulerClient.kt:12-18
   ...
```

- 경로는 worktree 기준 상대 경로다 (목록이 worktree 단위이므로 항상 그 안의 파일). 프로젝트 밖의 파일(라이브러리 소스 등)에는 메모를 달 수 없다.
- 줄 번호는 1부터 센다. 한 줄이면 `:29`로 쓴다.
- 코드 펜스 언어는 파일 타입에서 가져온다. 보낼 때의 현재 코드를 넣는다 (메모를 달 때의 코드가 아니다).
- Claude와 Codex에 같은 형식으로 보낸다.

## 기존 코드에서 쓸 것

- `terminal/TerminalIo.kt`의 `TerminalIo.submit(prompt)`: 여러 줄 프롬프트를 메시지 하나로 보내고 Enter를 친다.
- `api/PhantomAiApi.kt`: `sessions(project)`, `startSession`, `focusSession`.
- `sidebar/OpenWorktree.kt`: `newSession(worktree, kind, prompt)`.
- `mcp/IdeEditorBridge.kt`: 선택 범위와 줄 번호를 읽는 기존 코드 (참고용).
- 없는 것: "마지막에 쓴 세션"이라는 개념. 새로 둔다. 제 판단으로는 세션 탭이 포커스를 받거나 프롬프트가 보내질 때
  메모리에 기록하면 충분하다. 영속화는 필요 없다.

## 구현자에게 맡기는 것

- collaborationTools 부품을 쓸지, 같은 모양을 직접 그릴지.
- 메모 저장소의 형태: 프로젝트 서비스 하나, 메모리만, 문서마다 RangeMarker.
- 단축키 기본값 (목업은 ⌥↩ 예시이고, 기존 키맵과 겹치지 않는 것으로).
- 목록 패널이 툴 윈도우 안에서 차지하는 위치.
- 테스트 범위. 다만 최소한 프롬프트 조립(경로·범위·코드·메모 순서), 줄이 밀린 뒤 범위 추적, 세션 선택 기본값,
  보낸 메모 제거, 대기열(바쁜 세션 → DONE에 전송, 여러 개 순서대로, 취소, 세션 닫힘 시 되돌리기)은 테스트로 확인한다.

## 제가 정한 것 (사용자 확인 필요)

- 화면 세부: 노란 테두리 블록, 블록 머리 문구, 목록의 체크박스.
- 프롬프트 첫 줄 문구와 번호 형식.
- 보낸 메모는 지우고, 체크 안 한 메모는 남긴다.
- 범위의 코드가 전부 지워지면 메모도 지운다.
- 보내기 직전에 문서를 저장한다.
- 대기열 세부: 넣는 순간 프롬프트를 확정, 목록에 대기 항목으로 남김, 취소하면 메모 복원, 세션이 닫히면 되돌림, 하나씩 순서대로.
