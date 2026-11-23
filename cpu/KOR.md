# Phantom CPU Indicator

`dev.phantom.cpu` · [English](./README.md)

IDE가 느리거나 팬이 돌 때 IDE 탓인지 다른 프로그램 탓인지 상태바에서 바로 보고, IDE 탓이면 무엇 때문인지 확인합니다. 서드파티 CPU Usage Indicator(`krasa.cpu`)를 대체합니다. 그 플러그인은 상태바 그리기가 UI 멈춤 30건 넘게 EDT에 찍혔습니다(오프스크린 이미지 blit이 Metal 완료를 기다림, 페인트마다 새 `BufferedImage`).

- **상태바:** 최근 1분 그래프(시스템 회색, IDE 파랑)와 `IDE 12%` `시스템 64%`. 둘 다 컴퓨터 전체 대비 비율입니다. IDE가 70% 이상으로 10초 이어지면 IDE 숫자에 주황 배경이 붙습니다.
- **호버 팝업:** `IDE가 CPU 87% 사용 중`, 최근 10초 동안 IDE 안에서 CPU를 쓴 작업과 비율 막대(인덱싱, Kotlin/Java 코드 분석, GC, JIT, 기타), 시스템 상위 프로세스 3개, **스레드 덤프 저장** 링크(플랫폼의 Dump Threads 동작), 이 표시기 자체 CPU. 마우스가 벗어나면 닫힙니다.

## 측정 방법

2초마다, 전용 데몬 스레드 `Phantom CPU Indicator`에서 잽니다.

- IDE: 프로세스 CPU 시간(`OperatingSystemMXBean.getProcessCpuTime`)을 경과 시간과 코어 수로 나눈 값. 시스템: `getCpuLoad()`.
- 팝업이 열려 있을 때만: 스레드별 CPU 시간(`ThreadMXBean`)과 그동안 돈 스레드의 스택(최대 256프레임). 스레드 이름으로는 작업을 거의 알 수 없어서(대부분 `DefaultDispatcher-worker-N`, `ApplicationImpl pooled thread N`) 스택에서 가장 바깥쪽에 있는 알려진 프레임으로 정합니다. `com.intellij.util.indexing`의 인덱서·스캐너는 인덱싱, 데몬·인스펙션·자동완성·인레이 힌트·Kotlin Analysis API/FIR은 코드 분석입니다. 인덱스 Writer 스레드는 이름으로 인덱싱입니다.
- GC와 JIT는 `ThreadMXBean`에 안 나오는 VM 스레드에서 돕니다. 프로세스 CPU 중 Java 스레드 몫을 뺀 나머지를 MXBean이 알려 주는 GC 시간과 컴파일 시간 비율로 나누고, 남는 건 기타입니다.
- `ps -Ao pcpu,comm -r`은 팝업이 열려 있을 때만 실행합니다.

## 그리기

오프스크린 이미지를 쓰지 않고 그래프는 `Path2D` 선 두 개를 바로 그립니다. 글꼴, 색, 선 굵기, 경로, 글자 폭은 필드로 두고 테마나 글꼴이 바뀔 때만 다시 만듭니다. 숫자 문자열도 숫자가 바뀔 때만 만듭니다. 숫자나 그래프가 바뀔 때만 위젯 자기 영역만 다시 그리고, 창이 비활성이거나 최소화돼 있으면 그리지 않습니다(측정은 계속하고, 놓친 변경은 창이 활성화될 때 그립니다).
