# GitHub Support

`dev.phantom.github` · [English](./README.md)

IDE 안의 GitHub: GitHub와 GitHub Enterprise 계정(OAuth 또는 토큰), 클론, GitHub에 프로젝트 공유, 풀 리퀘스트(목록, diff와 에디터에서 코멘트를 다는 리뷰, 생성, 병합), Gist, GitHub에서 열기, GitHub Actions 워크플로 스키마.

GitHub로 로그인(브라우저 로그인)은 GitHub OAuth 클라이언트 id가 있을 때만 보입니다(빌드에 넣거나
`-Dphantom.github.oauth.client.id`로 지정). 없으면 계정 메뉴, 클론 대화상자, 풀 리퀘스트 도구 창에서 토큰 로그인만 제공합니다.

IntelliJ Community의 GitHub 플러그인(`plugins/github`, Apache 2.0)을 바탕으로 Phantom의 id로 만든 플러그인입니다. `org.jetbrains.plugins.github`는 별칭으로
남겨 두고 콘텐츠 모듈 이름도 IntelliJ 것을 그대로 써서, 이것에 의존하는 플러그인과 번들 모듈이 그대로 동작합니다. Phantom은
IntelliJ 플러그인 대신 이 빌드를 번들합니다.

**0.1.3**: 조직의 OAuth App 접근 제한 때문에 저장소가 보이지 않을 때, 오류 메시지가 JetBrains 지원 문서 대신 그 제한에 관한
GitHub 문서로 연결됩니다.
