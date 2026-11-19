# Phantom Profiler

`dev.phantom.profiler` · [한국어](./KOR.md)

CPU and allocation profiling for Java and Kotlin with Java Flight Recorder, which ships with the JDK. No agent is downloaded or attached.

- **Profile:** a run action next to Run and Debug for application configurations (Java, Kotlin, Spring Boot). The program starts with a JFR recording (`settings=profile`), and the recording opens when the program ends. A configuration that already starts JFR keeps its own options.
- **Profiler tool window:** JVMs running on this machine, with **Start Recording** and **Stop and Open** (through `jcmd` from the IDE's runtime), and the recordings saved so far.
- **Recording viewer:** any `.jfr` file opens with
  - **Flame Graph:** click a frame to zoom in, click an ancestor to zoom out, double-click to open the source line. The project's code is orange, the JDK blue, Kotlin violet, other libraries sand.
  - **Call Tree** opened along the heaviest path, **Methods** (self and total share, sortable), **Allocations** (flame graph by allocating stack, table by class), and a **Summary** with CPU, allocation and GC numbers.

Recordings are kept in the IDE system folder under `phantom-profiler`.

Needs a JVM with Flight Recorder: JDK 11 or later, or 8u262 or later. Profiling runs on remote targets, tests and Gradle-delegated runs is not supported yet; use Start Recording on the running JVM instead.
