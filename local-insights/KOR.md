# Phantom Local Insights

`dev.phantom.local-insights` · [English](./README.md)

IDE를 느리게 하는 원인을 이 컴퓨터 안에서만 보여 준다. **View | Tool Windows | Local Insights**를 연다.

- **Slowest Inspections**: **Code | Inspect Code** 같은 일괄 실행에서 돈 검사마다 실행 횟수, 합계 시간, 가장 오래 걸린
  시간. 검사를 골라 **Disable Inspection**을 누르면 프로젝트의 현재 검사 프로필에서 그 검사를 끈다.
- **Indexing by Plugin**: 인덱싱 중 파일 기반 인덱스가 값을 계산하는 데 쓴 시간을, 인덱스를 등록한 플러그인별로 합친
  값과 파일 수. IDE 플랫폼 자체의 인덱스(스텁 포함)는 *IDE platform*으로 묶는다.

**Refresh**는 최신 값을 읽고 **Clear**는 값을 지운다.

값은 IDE 세션 동안 메모리에만 있다. 플러그인은 파일을 쓰지 않고, 시작 작업도 네트워크 요청도 없다. 검사 실행이나
인덱싱이 끝날 때 IntelliJ가 이미 보내는 이벤트만 듣는다.

아직 없음: 편집기의 실시간 강조 중 도는 검사(IntelliJ가 그 시간을 내부 클래스에만 두고 이벤트를 보내지 않는다),
인덱싱 표에서 플러그인 끄기.
