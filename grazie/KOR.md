# Phantom Natural Languages

`dev.phantom.grazie` · [English](./README.md)

IDE 안의 모든 텍스트에 대한 맞춤법, 문법, 문체 검사: 주석, 문자열 리터럴, 문서, 커밋 메시지, Markdown, XML, JSON, YAML, properties, 일반 텍스트. 검사는 LanguageTool과 Hunspell로 로컬에서 이루어집니다. 영어는 기본으로 들어 있고, 다른 언어는 켤 때 내려받습니다(LanguageTool 번들은 resources.jetbrains.com에서, Hunspell 사전은 JetBrains의 grazie-platform-public Maven 저장소에서). Grazie Cloud는 `-Dphantom.jetbrains.services=true`를 주지 않으면 꺼져 있습니다.

IntelliJ Community의 Natural Languages 플러그인(`plugins/grazie`, Apache 2.0)을 바탕으로 Phantom의 id로 만든 플러그인입니다. `tanvd.grazi`는 별칭으로
남겨 두고 콘텐츠 모듈 이름도 IntelliJ 것을 그대로 써서, 이것에 의존하는 플러그인과 번들 모듈이 그대로 동작합니다. Phantom은
IntelliJ 플러그인 대신 이 빌드를 번들합니다.
