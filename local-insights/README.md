# Phantom Local Insights

`dev.phantom.local-insights` · [한국어](./KOR.md)

Shows what makes the IDE slow, on this machine only. Open **View | Tool Windows | Local Insights**:

- **Slowest Inspections**: every inspection that ran in a **Code | Inspect Code** (or other batch) run, with the number
  of runs, total and slowest time. Select one and press **Disable Inspection** to turn it off in the project's current
  inspection profile.
- **Indexing by Plugin**: the time file-based indexes spent computing values during indexing, summed per plugin that
  registered the index, with the number of files. Indexes of the IDE platform itself, stubs included, are grouped as
  *IDE platform*.

**Refresh** reads the latest numbers; **Clear** forgets them.

The numbers are kept in memory for the IDE session only. The plugin writes no files, has no startup activity and makes
no network requests; it only listens to the events IntelliJ already publishes when an inspection run or an indexing
session finishes.

Not yet: inspections run by on-the-fly editor highlighting (IntelliJ keeps those timings in internal classes and
publishes no event for them), and a way to disable a plugin from the indexing table.
