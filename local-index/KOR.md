# Phantom Local Index

`dev.phantom.local-index` · [English](./README.md)

같은 프로젝트의 체크아웃(워크트리, 클론) 사이에서 이 컴퓨터에 있는 인덱스 데이터를 재사용합니다. 다른 체크아웃이 이미 인덱싱한 파일을 새 체크아웃에서 다시 인덱싱하지 않습니다.

- **로컬 전용:** 데이터는 이 컴퓨터에서만 읽고 쓰며, 어디로도 전송하지 않습니다.
- **고급 설정:** Settings | Advanced Settings | Phantom Local Index | Cache size limit (MB), 기본값 2048. 캐시가 이 값을 넘으면 오래된 데이터부터 삭제합니다. 바꾼 값은 IDE를 다시 시작하면 적용됩니다.
- **끄기:** Settings | Plugins에서 플러그인을 비활성화합니다.

## 캐시 대상

파일 자신의 내용(과 이름, 파일 타입)만으로 결과가 정해지고 프로젝트 설정이나 다른 파일에 의존하지 않는 인덱스만 캐시합니다.

- `IdIndex`: 파일의 단어. 텍스트만 읽습니다.
- `java.fun.expression`, `java.null.method.argument`, `java.binary.plus.expression`: Java 파일 하나의 구문 트리 또는 토큰만 읽습니다.
- `Trigram.Index`: 텍스트의 트라이그램.
- Kotlin 소스 인덱스(`KotlinPackageSourcesMemberNamesIndex`, `KotlinPartialPackageNamesIndex`, `KotlinTopLevelCallableByPackageShortNameIndex`, `KotlinTopLevelClassLikeDeclarationByPackageShortNameIndex`): `.kt` 파일 하나의 패키지와 선언 이름.
- `Stubs`: Java 소스, 클래스 파일, Kotlin `.kt` 파일의 스텁 트리(플랫폼이 인덱싱 플레이버를 지정한 파일 타입과 Kotlin 소스). Kotlin `.kts` 스크립트는 스크립트 정의에 따라 스텁이 달라질 수 있어 캐시하지 않음.

캐시하지 않음: `TodoIndex`(TODO 패턴 설정에 의존), `FilenameIndex`와 `filetypes`(내용을 읽지 않음).

## 수명 주기

- 캐시는 한 번에 IDE 프로세스 하나만 소유합니다. 다른 IDE 프로세스가 이미 쓰고 있거나 열 수 없으면 이 프로세스는 캐시 없이 동작하며 경고를 로그에 남깁니다.
- 캐시가 손상돼도 직접 지울 필요가 없습니다. 읽을 수 없는 항목은 미스로 처리하고 삭제하며, 다른 플러그인 버전, 인덱스 버전, 인덱스 레이아웃으로 쓴 캐시는 시작할 때 비웁니다.
- 플랫폼이 인덱스를 다시 만들거나 초기화하면 그 인덱스의 캐시 데이터도 비웁니다.

## 진단

- 프로젝트 인덱싱이 끝날 때마다 IDE 로그(`idea.log`)에 한 줄이 남습니다. 예: `Phantom local index: demo: 190 of 200 index updates from cache (IdIndex 100/100, Stubs 90/100), about 2000 ms of indexing saved`. 절약 시간은 재사용한 데이터를 처음 계산하는 데 걸렸던 시간입니다.
- 레지스트리 키 `phantom.localindex.verify.one.in`(기본값 0 = 끔): N > 0이면 캐시 적중 N번마다 한 번씩 다시 계산해 비교합니다. 다르면 오류를 기록하고 해당 인덱스는 재시작 전까지 캐시를 쓰지 않습니다.
