# Phantom Grid

`dev.phantom.grid` · [English](./README.md)

다른 플러그인이 표를 보여 줄 때 쓰는 데이터 그리드입니다: 정렬, 필터, 페이지 나누기, 값 편집기로 셀 편집, 레코드 보기와 행·열
바꿔 보기, 열 통계, 그리고 추출기(CSV, TSV, HTML, XLSX, 스크립트)로 행을 복사하거나 내보내기. CSV와 TSV 파일은 텍스트 편집기
옆에서 표로 열립니다. Phantom의 ai-integrations와 mybatis 플러그인이 이것을 바탕으로 동작합니다.

IntelliJ Community의 Grid Core 플러그인(`grid`, Apache 2.0)을 바탕으로 Phantom의 id로 만든 플러그인이며, 사용 통계는
뺐습니다. `intellij.grid.core.plugin`은 별칭으로 남겨 두고 콘텐츠 모듈 이름도 IntelliJ 것을 그대로 써서, 이것에 의존하는
플러그인이 그대로 동작합니다. 모듈들이 패키지를 나눠 쓰므로 모두 한 클래스 로더(embedded)로 읽힙니다. 사용 통계 리스너만
들어 있던 `intellij.grid.impl.ide`는 뺐습니다. Phantom은 IntelliJ 플러그인 대신 이 빌드를 번들합니다.
