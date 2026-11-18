# Phantom Grammar

`dev.phantom.grammar` · [English](./README.md)

IDE 안의 모든 텍스트에 대한 문법, 문체, 맞춤법 검사: 주석, 문자열 리터럴, 문서, 커밋 메시지, Markdown, XML, JSON, YAML,
properties, 일반 텍스트. 문법과 문체는 [LanguageTool](https://languagetool.org/)(LGPL 2.1)이 IDE 안에서 검사합니다. 영어는
기본으로 들어 있고, 다른 언어는 켤 때 내려받습니다(LanguageTool 번들은 이 저장소의 GitHub 릴리스
`languagetool-6.8.27`에서). 맞춤법은 LanguageTool의
영어 사전, IDE의 사전, 켜 둔 다른 언어의 LanguageTool 맞춤법 검사기, 직접 추가한 Hunspell 사전으로 검사합니다. 텍스트가 IDE
밖으로 나가지 않습니다.

오픈 소스 코드만으로 만들었습니다. IntelliJ Community의 Natural Languages 플러그인(`plugins/grazie`, Apache 2.0)의 IDE 부분
(텍스트 추출, 인스펙션, 빠른 수정, 설정, `com.intellij.grazie.*` 확장 지점)과 LanguageTool입니다. JetBrains의 소스 비공개
Grazie 라이브러리(`ai.grazie.*`)는 쓰지 않습니다. IDE 부분이 거기서 가져다 쓰던 것(언어 목록, 언어 감지, 문장 나누기, 코드
감지, 맞춤법 엔진)은 이 플러그인의 코드(`com.intellij.grazie.nlp`, `com.intellij.grazie.spellcheck`)입니다. IntelliJ 플러그인
(그리고 이 플러그인이 대신하는 Phantom Natural Languages, `dev.phantom.grazie`)과 비교하면 Grazie 규칙 엔진 검사와 문체
프로필, 자동 수정, 독일어·러시아어·우크라이나어 Hunspell 사전 내려받기, JetBrains YouTrack 신고가 없습니다.

`tanvd.grazi`와 `dev.phantom.grazie`는 별칭으로 남겨 두고 콘텐츠 모듈 이름도 IntelliJ 것을 그대로 써서, 이것에 의존하는
플러그인과 번들 모듈(Kotlin, Python, TOML, Maven, YAML, Markdown 등)이 그대로 동작합니다.

**0.2.1**: 다른 언어의 LanguageTool 번들을 resources.jetbrains.com 대신 이 저장소의 GitHub 릴리스 `languagetool-6.8.27`에서
내려받습니다. 파일과 체크섬은 같습니다. 0.2.0은
대문자 파일 이름(`DE-6.8.27.jar`)을 요청해 서버가 거절했는데, 이제 다시 내려받아집니다. 플러그인 jar의 `META-INF/THIRD-PARTY-NOTICES.txt`에 라이브러리와 라이선스 목록이
있고, `META-INF/licenses/`에 LGPL, GPL 3.0(LGPL 3.0의 바탕), MIT, BSD 전문이 들어 있습니다. LGPL 소스를 받는 곳은 NOTICE에
있습니다.
