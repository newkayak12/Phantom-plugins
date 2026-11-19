# Phantom Profiler

`dev.phantom.profiler` · [English](./README.md)

JDK에 들어 있는 Java Flight Recorder(JFR)로 Java·Kotlin의 CPU와 메모리 할당을 프로파일링합니다. 따로 내려받거나 붙이는 에이전트는 없습니다.

- **Profile:** Run, Debug 옆에 실행 동작이 하나 더 생깁니다(Java, Kotlin, Spring Boot 애플리케이션 구성). JFR 녹화(`settings=profile`)를 켠 채로 프로그램을 실행하고, 프로그램이 끝나면 녹화를 엽니다. 이미 JFR 옵션이 있는 구성은 그 옵션을 그대로 씁니다.
- **Profiler 도구 창:** 이 맥에서 돌고 있는 JVM 목록에서 **Start Recording**, **Stop and Open**을 합니다(IDE 런타임의 `jcmd` 사용). 지금까지 저장된 녹화도 보여 줍니다.
- **녹화 보기:** `.jfr` 파일을 열면 아래가 나옵니다.
  - **Flame Graph:** 프레임을 누르면 확대, 위쪽 조상을 누르면 축소, 두 번 누르면 소스 줄로 갑니다. 프로젝트 코드는 주황, JDK는 파랑, Kotlin은 보라, 그 밖의 라이브러리는 모래색입니다.
  - 가장 무거운 경로로 펼쳐진 **Call Tree**, **Methods**(self·total 비율, 정렬 가능), **Allocations**(할당한 스택의 플레임 그래프와 클래스별 표), CPU·할당·GC 수치를 담은 **Summary**
  
녹화 파일은 IDE 시스템 폴더의 `phantom-profiler` 아래에 남습니다.

Flight Recorder가 있는 JVM이 필요합니다(JDK 11 이상, 또는 8u262 이상). 원격 실행, 테스트, Gradle에 위임한 실행은 아직 Profile로 띄울 수 없습니다. 이럴 때는 실행 중인 JVM에서 Start Recording을 쓰면 됩니다.
