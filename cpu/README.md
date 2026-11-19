# Phantom CPU Indicator

`dev.phantom.cpu` · [한국어](./KOR.md)

Shows in the status bar whether the IDE or something else is using the CPU, and when it is the IDE, on what. Replaces the CPU Usage Indicator plugin (`krasa.cpu`), whose status bar painting showed up on the EDT in over 30 UI freezes (offscreen image blits waiting on Metal, a new `BufferedImage` per paint).

- **Status bar:** a one-minute graph (system grey, IDE blue) and `IDE 12%` `시스템 64%`, both in percent of the whole machine. When the IDE stays at 70% or more for 10 seconds its number gets an orange background.
- **Hover popup:** `IDE가 CPU 87% 사용 중`, what used the IDE's CPU in the last 10 seconds with a bar each (indexing, Kotlin/Java code analysis, GC, JIT, other), the three busiest processes on the machine, a **스레드 덤프 저장** link (the platform's Dump Threads action), and this plugin's own CPU. Closes when the mouse leaves.

## How it measures

Every 2 seconds, on its own daemon thread `Phantom CPU Indicator`:

- IDE: the process CPU time (`OperatingSystemMXBean.getProcessCpuTime`) over wall time and cores. System: `getCpuLoad()`.
- Only while the popup is open or the IDE is busy: CPU time of every thread (`ThreadMXBean`), and the stacks of the threads that ran. Thread names rarely say what the work is (most runs on `DefaultDispatcher-worker-N` / `ApplicationImpl pooled thread N`), so the outermost recognised frame decides: indexer and scanner classes in `com.intellij.util.indexing` are indexing, the daemon, inspections, completion, inlay hints and Kotlin analysis API/FIR are code analysis. The index writer threads are indexing by name.
- GC and JIT run on VM threads that `ThreadMXBean` does not list. The process time not on Java threads is split between them by the GC and compilation time the MXBeans report; what is left is other.
- `ps -Ao pcpu,comm -r` only while the popup is open.

## Painting

No offscreen image: the graph is two `Path2D` lines drawn directly. Font, colors, stroke, paths and text widths are fields rebuilt only on a theme or font change, and the numbers' strings change only when a number does. The widget repaints only itself, only when a number or the graph changed, and not while its window is inactive or minimised (sampling continues, and a missed change is painted on activation).
