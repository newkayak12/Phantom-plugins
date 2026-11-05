# AI 메모 일괄 전송 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 에디터의 줄이나 범위에 AI 명령 메모를 달고, 그 worktree의 메모를 프롬프트 하나로 묶어 세션에 보낸다. 바쁜 세션이면 대기열에 넣었다가 끝나면 보낸다.

**Architecture:** 새 패키지 `dev.phantom.ai.memo` 하나에 모은다. 순수 로직(프롬프트 조립, 세션 후보, 대기열)은 플랫폼 없이 테스트할 수 있게 인터페이스로 분리한다. 프로젝트 서비스 `AiMemoStore`가 메모를 RangeMarker로 들고 있다(메모리만, 영속화 없음). 에디터 표시(범위 테두리, 인라인 블록, 거터 `+`)와 툴 윈도우 목록은 store를 구독한다. 전송은 기존 `TerminalIo.submit`과 `AgentTerminalService.launch(prompt=)`를 쓴다.

**Tech Stack:** Kotlin, IntelliJ Platform 262 (Phantom 포크), `intellij.platform.collaborationTools` (본체 lib), JUnit3 스타일(`BasePlatformTestCase`, `junit.framework.TestCase`)

**Spec:** `docs/superpowers/specs/2026-11-05-ai-memo-batch-design.md` (목업: `docs/superpowers/specs/2026-11-05-ai-memo-batch/mock.png`)

## Global Constraints

- 대상 플러그인: `ai-integrations` (현재 0.3.12 → 마지막 태스크에서 0.3.13).
- 메모는 일회용이다. 메모리에만 두고 `AiState`나 디스크에 저장하지 않는다.
- 메모 목록과 보내기는 프로젝트 창 단위다. worktree는 `project.basePath`다(`AgentToolWindowPanel.newSession`과 같다).
- 프롬프트 경로는 worktree 기준 상대 경로, 줄 번호는 1부터, 한 줄이면 `:29`, 범위면 `:29-33`.
- 프롬프트 첫 줄은 정확히 `아래 코드 위치마다 요청을 처리해줘.`, 메모 줄은 `   → <메모>`로 쓴다.
- 바쁜 세션(`AgentStatus.WORKING`/`WAITING`)에는 끼어들지 않는다. 대기열에 넣고 `DONE`이 되면 하나씩 보낸다.
- 보내기 직전에 메모가 달린 문서를 저장한다(`FileDocumentManager.saveDocument`).
- `collaborationTools`의 internal/experimental API는 써도 된다(Phantom은 업스트림을 따라가지 않는다).
- 사용자에게 보이는 문구는 `PhantomAiBundle.properties`에 `# --- memo ---` 블록으로 넣는다.
- 커밋 author/committer: `newkayak12 <newkayak12@gmail.com>`. 메시지 끝에 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- 테스트 실행: `./gradlew --no-configuration-cache -PplatformFromRepository=true :ai-integrations:test --tests '<패턴>'` (저장소 루트 `~/orca/Phantom-plugins`에서).

## Review Focus

1. 코드에 ```` ``` ````가 들어 있는 메모: 코드 펜스가 깨지지 않아야 한다. 펜스는 코드 안의 가장 긴 백틱 연속보다 길게 쓴다 → Task 1 `testFenceIsLongerThanBackticksInCode`.
2. 코드나 메모에 ESC(`\u001b`) 문자(예: `\u001b[201~`): 브래킷 붙여넣기를 일찍 끝내 버린다. 프롬프트에서 ESC를 지운다 → Task 1 `testEscapeCharactersAreRemoved`.
3. worktree 밖의 파일(라이브러리 소스, 스크래치)에서 "AI 메모 추가": 액션이 비활성이어야 한다 → Task 5 `testAddActionDisabledOutsideWorktree`.
4. 체크된 메모가 0개일 때 보내기: 버튼이 비활성이고 아무것도 보내지 않는다 → Task 6 `testSendDisabledWithNothingChecked`.
5. 파일을 닫았다가 다시 열기: 메모 블록이 다시 보여야 한다 → Task 5 `testBlocksComeBackWhenFileReopens`.

---

### Task 1: 프롬프트 조립 (순수 로직)

**Files:**
- Create: `ai-integrations/src/main/kotlin/dev/phantom/ai/memo/MemoPrompt.kt`
- Test: `ai-integrations/src/test/kotlin/dev/phantom/ai/MemoPromptTest.kt` (`junit.framework.TestCase`)

**Interfaces:**
- Produces:
  - `data class MemoItem(val path: String, val startLine: Int, val endLine: Int, val language: String, val code: String, val text: String)`: 줄은 1부터, `path`는 worktree 기준 상대 경로, `language`는 펜스 언어(빈 문자열 가능)
  - `object MemoPrompt { fun build(items: List<MemoItem>): String }`

- [ ] **Step 1: 실패하는 테스트 작성**

```kotlin
fun testTwoItemsInOrder() {
  val p = MemoPrompt.build(listOf(
    MemoItem("src/A.java", 29, 33, "java", "a()\n\nb()", "둘 다 hasText로"),
    MemoItem("src/B.kt", 12, 12, "kotlin", "c()", "코루틴으로"),
  ))
  assertEquals(
    "아래 코드 위치마다 요청을 처리해줘.\n\n" +
    "1. src/A.java:29-33\n   ```java\n   a()\n\n   b()\n   ```\n   → 둘 다 hasText로\n\n" +
    "2. src/B.kt:12\n   ```kotlin\n   c()\n   ```\n   → 코루틴으로", p)
}
fun testFenceIsLongerThanBackticksInCode() {
  val p = MemoPrompt.build(listOf(MemoItem("R.md", 1, 3, "", "```\nx\n```", "고쳐")))
  assertTrue(p, p.contains("   ````\n   ```\n   x\n   ```\n   ````"))
}
fun testEscapeCharactersAreRemoved() {
  val p = MemoPrompt.build(listOf(MemoItem("A", 1, 1, "", "a\u001b[201~b", "m\u001bn")))
  assertFalse(p.contains('\u001b')); assertTrue(p.contains("a[201~b")); assertTrue(p.contains("→ mn"))
}
fun testMultiLineMemoIsIndented() {
  val p = MemoPrompt.build(listOf(MemoItem("A", 1, 1, "", "x", "첫줄\n둘째줄")))
  assertTrue(p, p.endsWith("   → 첫줄\n     둘째줄"))
}
```

- [ ] **Step 2: 실패 확인**: `--tests '*MemoPromptTest*'` → 컴파일 실패(`MemoItem` 없음)
- [ ] **Step 3: `MemoPrompt.build` 구현**: 항목을 `\n\n`으로 잇는다. 코드의 빈 줄은 들여쓰기 없이 빈 줄로 둔다(테스트 1). 펜스 길이는 `max(3, 코드 안의 가장 긴 백틱 연속 + 1)`이다. 메모의 둘째 줄부터는 5칸 들여쓴다.
- [ ] **Step 4: 통과 확인**: 같은 명령 → 4 tests PASS
- [ ] **Step 5: 커밋** `ai-integrations: AI memo prompt builder`

---

### Task 2: 메모 저장소 (RangeMarker 추적)

**Files:**
- Create: `ai-integrations/src/main/kotlin/dev/phantom/ai/memo/AiMemoStore.kt`
- Test: `ai-integrations/src/test/kotlin/dev/phantom/ai/AiMemoStoreInIdeTest.kt` (`BasePlatformTestCase`)

**Interfaces:**
- Consumes: Task 1 `MemoItem`
- Produces:
  - `class AiMemo(val id: Long, val file: VirtualFile, val marker: RangeMarker, var text: String, var checked: Boolean = true)`, `val startLine: Int`, `val endLine: Int` (0부터, marker에서 계산)
  - `@Service(Service.Level.PROJECT) class AiMemoStore(project: Project) : Disposable`
    - `fun add(document: Document, startLine: Int, endLine: Int, text: String): AiMemo` (줄 0부터, 포함)
    - `fun update(id: Long, text: String)`, `fun setChecked(id: Long, checked: Boolean)`, `fun remove(ids: Collection<Long>)`
    - `fun all(): List<AiMemo>`: 파일 경로, 시작 줄 순서
    - `fun inFile(file: VirtualFile): List<AiMemo>`
    - `fun toItem(memo: AiMemo): MemoItem?`: 지금 문서의 코드로 만들고, worktree 밖이면 null
    - `fun worktree(): String?` = `project.basePath`
    - `companion object { val TOPIC: Topic<AiMemoListener>; fun getInstance(project: Project): AiMemoStore }`
  - `fun interface AiMemoListener { fun changed() }`
- `plugin.xml`은 수정하지 않는다(`@Service` 경량 서비스).

- [ ] **Step 1: 실패하는 테스트 작성**: `myFixture.configureByText("A.java", ...)`로 만든다.
  - `testAddAndList`: 픽스처의 2–4줄(0부터)에 add → `all().single()`의 `startLine==2`, `endLine==4`, `text`가 일치. `toItem`의 `path=="A.java"`, `startLine==3`(1부터), `code`는 그 세 줄. 픽스처의 temp 루트를 worktree로 쓰기 위해 테스트용 생성자 `AiMemoStore(project, root: () -> String?)`를 둔다(기본은 `{ project.basePath }`)
  - `testRangeFollowsEdits`: 메모 위에 두 줄을 삽입(`WriteCommandAction`) → `startLine==4`, `endLine==6`
  - `testMemoRemovedWhenItsCodeIsDeleted`: 범위 전체를 지움 → `all().isEmpty()`, 리스너 1회 이상 호출
  - `testOrderAcrossFiles`: B.kt 1줄, A.java 5줄, A.java 1줄 → 순서 A:1, A:5, B:1
  - `testCodeIsCurrentAtToItemTime`: add 후 범위 안을 수정 → `toItem().code`에 수정된 내용
- [ ] **Step 2: 실패 확인**: `--tests '*AiMemoStoreInIdeTest*'` → 컴파일 실패
- [ ] **Step 3: 구현**: marker는 `document.createRangeMarker(lineStart(start), lineEnd(end))`이고 `isGreedyToRight=true`다. 메모를 지우는 조건은 `DocumentListener.documentChanged` 뒤에 `!marker.isValid || marker.startOffset == marker.endOffset`이다. 언어는 `file.fileType.name.lowercase()`로 하고, `PLAIN_TEXT`이면 빈 문자열로 둔다. 변경마다 `TOPIC`에 publish한다.
- [ ] **Step 4: 통과 확인**: 5 tests PASS
- [ ] **Step 5: 커밋** `ai-integrations: AI memo store with range tracking`

---

### Task 3: 세션 대기열 (바쁘면 대기, DONE에 전송)

**Files:**
- Create: `ai-integrations/src/main/kotlin/dev/phantom/ai/memo/MemoQueue.kt`
- Test: `ai-integrations/src/test/kotlin/dev/phantom/ai/MemoQueueTest.kt` (`junit.framework.TestCase`, 플랫폼 없이)

**Interfaces:**
- Consumes: Task 1 `MemoItem`, 기존 `AgentStatus`
- Produces:
  - `class QueuedBatch(val id: Long, val tabId: String, val prompt: String, val items: List<MemoItem>)`
  - `interface MemoTerminals { fun status(tabId: String): AgentStatus?; fun alive(tabId: String): Boolean; fun submit(tabId: String, prompt: String): Boolean }`
  - `class MemoQueue(private val terminals: MemoTerminals, private val onReturned: (QueuedBatch) -> Unit)`
    - `fun offer(tabId: String, prompt: String, items: List<MemoItem>): Boolean`: 바로 보냈으면 true, 대기열에 넣었으면 false
    - `fun pending(): List<QueuedBatch>`
    - `fun cancel(batchId: Long): QueuedBatch?`
    - `fun sessionChanged(tabId: String)`: 세션 상태가 바뀌거나 사라질 때 호출한다
  - 바쁨 판정: `status == WORKING || status == WAITING`. `null`이나 `DONE`이면 대기열이 비었을 때 바로 보낸다.

- [ ] **Step 1: 실패하는 테스트 작성** (가짜 `MemoTerminals`: status 맵, alive 집합, submit 기록)
  - `testIdleSessionGetsItNow`: status DONE → `offer`가 true, submit 1회
  - `testBusySessionQueuesAndSendsOnDone`: WORKING → false, submit 0회 → status=DONE, `sessionChanged` → submit 1회, `pending()` 비어 있음
  - `testWaitingIsBusyToo`: WAITING → 대기
  - `testOneBatchPerDone`: WORKING일 때 두 개 offer → DONE, `sessionChanged` → submit 1회(첫 번째). status=WORKING, `sessionChanged` → 그대로. DONE, `sessionChanged` → 두 번째 전송
  - `testCancelReturnsBatch`: 대기 중인 것을 cancel → 반환됨, 그 뒤 DONE이 와도 submit 0회
  - `testClosedSessionReturnsBatches`: 대기 중에 `alive=false`, `sessionChanged` → `onReturned`가 두 번 호출되고 `pending()` 비어 있음
  - `testQueueAheadKeepsOrder`: 대기열에 하나가 있는 상태(WORKING)에서 DONE인데도 새 offer는 줄 뒤에 선다 → 첫 `sessionChanged`에서 먼저 넣은 것이 나간다
- [ ] **Step 2: 실패 확인**: `--tests '*MemoQueueTest*'`
- [ ] **Step 3: 구현**: 탭마다 `ArrayDeque`를 둔다. 같은 탭에서 연속으로 DONE 이벤트가 와도, 하나를 보낸 뒤 그 탭이 WORKING/WAITING를 한 번 거치기 전까지는 다음 것을 보내지 않는다. 이를 위해 탭마다 `sentAwaitingBusy` 플래그를 둔다. 보낸 직전 이벤트가 그대로 DONE이면 두 번째가 바로 나가는 걸 막는다(`testOneBatchPerDone`).
- [ ] **Step 4: 통과 확인**: 7 tests PASS
- [ ] **Step 5: 커밋** `ai-integrations: AI memo queue for busy sessions`

---

### Task 4: 보내기 (세션 후보, 마지막 세션, 저장, 전송/대기/새 세션)

**Files:**
- Create: `ai-integrations/src/main/kotlin/dev/phantom/ai/memo/MemoSender.kt`
- Test: `ai-integrations/src/test/kotlin/dev/phantom/ai/MemoSenderInIdeTest.kt` (`BasePlatformTestCase`), 순수 부분은 `MemoChoicesTest.kt` (`TestCase`)

**Interfaces:**
- Consumes: Task 1~3, 기존 `SessionStore`(`forWorktree`, `TOPIC`), `AgentTabsHost`(`get(id)?.io`, `selected()`, 리스너), `AgentTerminalService.launch(worktree, kind, prompt=)`, `focus(tabId)`, `TerminalIo.submit`
- Produces:
  - `sealed interface MemoTarget { data class Session(val tabId: String, val label: String, val status: AgentStatus?) : MemoTarget; data class New(val kind: AgentKind) : MemoTarget }`
  - `object MemoChoices { fun of(sessions: List<AgentSession>, lastUsed: String?): Pair<List<MemoTarget>, Int> }`: 세션들(시작 순), 그다음 `New(CLAUDE)`, `New(CODEX)`. 기본 인덱스는 lastUsed가 목록에 있으면 그것, 없으면 첫 세션, 세션이 없으면 `New(CLAUDE)`
  - `enum class SendOutcome { SENT, QUEUED, STARTED, FAILED }`
  - `@Service(Service.Level.PROJECT) class MemoSender(project: Project)`
    - `fun choices(): Pair<List<MemoTarget>, Int>`
    - `@RequiresEdt fun send(target: MemoTarget, memoIds: List<Long>): SendOutcome`: 문서 저장 → `toItem` → `MemoPrompt.build` → Session이면 `queue.offer`, New면 `launch(prompt=)` → 보낸/대기열에 넣은 메모를 store에서 remove → lastUsed 갱신 → SENT/STARTED면 `focus`
    - `fun queue(): MemoQueue`: 이 프로젝트의 대기열. `SessionStore.TOPIC`을 구독해 `sessionChanged`를 호출한다
    - `fun lastUsed(): String?`: `AgentTabsHost`의 선택이 바뀔 때와 `send` 때 기록(메모리)
  - 대기열에서 되돌아온 묶음(`onReturned`)은 `items`의 경로·줄로 store에 다시 add한다(줄은 문서 길이에 맞춰 자른다). 그리고 알림을 띄운다.
  - 대기열에 넣을 때 알림(INFORMATION): `memo.queued={0} {1}: 끝나면 메모 {2}개를 보냅니다` (세션 라벨, 상태 문구, 개수)
- [ ] **Step 1: 실패하는 테스트 작성**
  - `MemoChoicesTest.testDefaultIsLastUsed`, `testDefaultFallsBackToFirstSession`, `testNoSessionsDefaultsToNewClaude`: 목록 순서와 기본 인덱스를 확인
  - `MemoSenderInIdeTest`(`AgentTerminalInIdeTest`처럼 `replaceStarter`로 가짜 터미널을 쓰고, 탭 io는 `send` 기록용 가짜 `TerminalIo`):
    - `testSendToIdleSessionSubmitsAndRemovesMemos`: 세션 DONE → `send` == SENT, 가짜 io가 `"\u001b[200~아래 코드 위치마다"`로 시작하는 붙여넣기를 받음, store 비어 있음
    - `testBusySessionQueuesAndRemovesMemos`: WORKING → QUEUED, io에 전송 없음, store 비어 있음, `queue().pending().size==1`
    - `testNewSessionGetsPromptAtLaunch`: `New(CLAUDE)` → STARTED, 가짜 starter의 command에 프롬프트 첫 줄이 들어 있음
    - `testUncheckedMemosStay`: 2개 중 1개만 보냄 → 나머지 1개 남음
    - `testDocumentsAreSavedBeforeSend`: 수정된 문서 → send 후 `FileDocumentManager.isDocumentUnsaved == false`
    - `testReturnedBatchRestoresMemos`: QUEUED 뒤 세션 탭을 닫음 → store에 같은 줄의 메모가 다시 생김
- [ ] **Step 2: 실패 확인**: `--tests '*MemoChoicesTest*' --tests '*MemoSenderInIdeTest*'`
- [ ] **Step 3: 구현**: 위 Interfaces대로 구현한다. `MemoTerminals` 실구현은 `SessionStore.get(tabId)?.status`, `AgentTabsHost.get(tabId) != null`, `get(tabId)?.io?.submit(prompt)`이다.
- [ ] **Step 4: 통과 확인**: 9 tests PASS
- [ ] **Step 5: 커밋** `ai-integrations: send AI memos to a session or queue them`

---

### Task 5: 에디터 UI (추가 액션, 거터 `+`, 범위 테두리 + 인라인 블록)

**Files:**
- Create: `ai-integrations/src/main/kotlin/dev/phantom/ai/memo/AddAiMemoAction.kt`, `MemoEditorBlocks.kt` (에디터마다 inlay/하이라이터/거터를 관리), `MemoBlockComponent.kt` (입력·표시 블록 Swing 컴포넌트)
- Modify: `ai-integrations/src/main/resources/META-INF/plugin.xml` (액션 등록, `editorFactoryListener`), `PhantomAiBundle.properties` (`# --- memo ---`)
- Test: `ai-integrations/src/test/kotlin/dev/phantom/ai/MemoEditorInIdeTest.kt`

**Interfaces:**
- Consumes: Task 2 `AiMemoStore`, Task 4 `MemoSender`(⌘↩ = 저장하고 보내기 흐름의 팝업 열기: Task 6의 `MemoSendPopup.show(project, editor)`. Task 5에서는 이 호출 지점만 두고, Task 6 전까지는 아무 일도 하지 않는다)
- Produces:
  - 액션 id `dev.phantom.ai.AddAiMemo`, 텍스트 `AI 메모 추가`, 그룹 `EditorPopupMenu`, 키 ⌥↩가 기존 키맵(Show Intention Actions)과 겹치므로 기본 키는 `ctrl alt M`(mac `meta alt M`)으로 한다. 겹치면 구현자가 바꾸고 계획에 적는다.
  - `class MemoEditorBlocks(editor: Editor, store: AiMemoStore) : Disposable`: `fun openInput(startLine: Int, endLine: Int)`, `@TestOnly fun blockCount(): Int`, `@TestOnly fun inputOpen(): Boolean`
  - 액션 동작: 선택이 있으면 그 줄 범위, 없으면 커서 줄 하나로 `openInput`. 입력칸에서 ↩ 저장(`store.add`), ⇧↩ 줄바꿈, Esc 취소, ⌘↩ 저장 후 보내기 팝업.
  - 표시: 저장된 메모의 범위를 노란 테두리로 묶고, 그 아래에 블록을 붙인다. 머리는 `AI 메모 n/N · a–b줄`과 편집/삭제 버튼, 본문은 메모 텍스트다. 입력 중일 때는 테두리가 파란색이다. 거터에서 줄에 마우스를 올리면 `+`가 보이고, 클릭이나 드래그로 범위를 잡아 `openInput`한다. collaborationTools의 `CodeReviewEditorGutterControlsRenderer`, `CodeReviewEditorInlayRangeOutlineUtils`, `CodeReviewComponentInlayRenderer`, `CodeReviewCommentTextFieldFactory`를 우선 시도한다. 맞지 않으면 `EditorCustomElementRenderer`와 `RangeHighlighter`로 같은 모양을 그린다. 목업 ①②가 기준이다.
- [ ] **Step 1: 실패하는 테스트 작성**
  - `testActionWithSelectionOpensInputForThoseLines`: 2–4줄 선택 → 액션 → `inputOpen()`, 저장 시뮬레이션 → store의 범위가 2..4
  - `testActionWithoutSelectionUsesCaretLine`
  - `testAddActionDisabledOutsideWorktree`: worktree 밖의 `LightVirtualFile` 에디터 → `presentation.isEnabled == false`
  - `testOneBlockPerMemo`: store에 2개 add → `blockCount()==2`. 하나 remove → 1
  - `testBlocksComeBackWhenFileReopens`: 메모를 단 파일의 에디터를 닫았다가 다시 열기 → `blockCount()==1`
- [ ] **Step 2: 실패 확인**: `--tests '*MemoEditorInIdeTest*'`
- [ ] **Step 3: 구현**: 위 Interfaces대로 구현한다. `EditorFactoryListener.editorCreated`에서 그 파일에 메모가 있거나 생길 때 `MemoEditorBlocks`를 붙이고, `editorReleased`에서 dispose한다.
- [ ] **Step 4: 통과 확인**: 5 tests PASS. 그리고 `./gradlew :ai-integrations:runIde`로 직접 띄워 목업 ①②와 비교한 스크린샷을 남긴다(`build/memo-editor.png`).
- [ ] **Step 5: 커밋** `ai-integrations: AI memo blocks in the editor`

---

### Task 6: 툴 윈도우 목록 + 세션 선택 팝업

**Files:**
- Create: `ai-integrations/src/main/kotlin/dev/phantom/ai/memo/MemoListPanel.kt`, `MemoSendPopup.kt`
- Modify: `ai-integrations/src/main/kotlin/dev/phantom/ai/toolwindow/AgentToolWindowPanel.kt` (목록을 붙일 자리. 트리 열 아래, 메모가 있을 때만 보임), `PhantomAiBundle.properties`
- Test: `ai-integrations/src/test/kotlin/dev/phantom/ai/MemoListInIdeTest.kt`

**Interfaces:**
- Consumes: Task 2 `AiMemoStore`, Task 4 `MemoSender.choices()/send()/queue()`
- Produces:
  - `class MemoListPanel(project: Project) : JPanel`: 항목마다 체크박스, `파일명 a–b`, 메모 텍스트. 클릭하면 그 위치로 이동. 대기 중인 묶음은 `대기 중 → <세션 라벨>` 항목과 취소 버튼. 아래쪽 버튼 `memo.send={0}개 한 번에 보내기`
    - `@TestOnly fun rowsForTest(): List<String>`, `@TestOnly fun sendButtonForTest(): JButton`
  - `object MemoSendPopup { fun show(project: Project, anchor: Component?) }`: `MemoChoices` 목록을 보여준다. 세션 항목 문구는 `<라벨> · <상태>`이고 상태는 `일하는 중`/`응답 대기 중`/`완료`, 새 세션은 `새 Claude 세션`/`새 Codex 세션`이다. 기본 선택은 인덱스. 고르면 체크된 메모 id로 `MemoSender.send`
- [ ] **Step 1: 실패하는 테스트 작성**
  - `testListShowsMemosInOrder`: 2개 add → `rowsForTest()`가 `["A.java 3–5 · m1", "A.java 9 · m2"]` 형식으로 순서대로
  - `testPanelHiddenWithoutMemos`: 메모 0개 → `isVisible==false`. add → true
  - `testSendDisabledWithNothingChecked`: 전부 체크 해제 → `sendButtonForTest().isEnabled == false`
  - `testQueuedBatchShowsAndCancelRestores`: 대기 상태를 만든 뒤 → 행에 `대기 중 →`. 취소 → 메모 행이 돌아옴
- [ ] **Step 2: 실패 확인**: `--tests '*MemoListInIdeTest*'`
- [ ] **Step 3: 구현**: 위 Interfaces대로 구현한다. Task 5의 ⌘↩ 호출 지점을 `MemoSendPopup.show`에 연결한다.
- [ ] **Step 4: 통과 확인**: 4 tests PASS. `runIde`에서 메모 3개를 단다 → 보내기 → 가짜가 아닌 실제 Claude 세션에 프롬프트가 들어가는지 수동 확인하고, 스크린샷 `build/memo-send.png`를 남긴다.
- [ ] **Step 5: 커밋** `ai-integrations: AI memo list and session picker`

---

### Task 7: 마무리 (버전, 문서, 전체 검증)

**Files:**
- Modify: `ai-integrations/build.gradle.kts` (`version = "0.3.13"`), `.phantom/marketplace.json` (ai-integrations 항목의 version, 설명의 버전 표기, download 경로를 0.3.13으로. 설명에 AI 메모 한 문장 추가), `ai-integrations/README.md`, `ai-integrations/KOR.md` (기능 목록에 AI 메모)

- [ ] **Step 1: 전체 테스트 + 빌드**: `./gradlew --no-configuration-cache -PplatformFromRepository=true :ai-integrations:test :ai-integrations:buildPlugin` → BUILD SUCCESSFUL, 기존 테스트 포함 실패 0
- [ ] **Step 2: 의존 플러그인 확인**: `ai-visualization`은 `ai-integrations`를 localPlugin으로 쓴다. `:ai-visualization:test`가 여전히 통과하는지 확인한다
- [ ] **Step 3: 커밋** `ai-integrations 0.3.13: AI memos ...` (기존 릴리스 커밋처럼 동작을 서술하는 한 문단과 `Marketplace entry at 0.3.13.`)
