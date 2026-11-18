# Phantom Grid

`dev.phantom.grid` · [한국어](./KOR.md)

The data grid that other plugins show tables in: sorting, filtering, paging, cell editing with value editors, record and
transposed views, column statistics, and copying or exporting rows through extractors (CSV, TSV, HTML, XLSX and
scripts). CSV and TSV files open as a table next to the text editor. Phantom's ai-integrations and mybatis build on it.

Based on the IntelliJ Community Grid Core plugin (`grid`, Apache 2.0), under Phantom's own id, without its usage
statistics. `intellij.grid.core.plugin` stays as an alias and the content module names stay the IntelliJ ones, so plugins
that depend on them keep working. The modules share packages, so all of them load in one class loader (embedded).
`intellij.grid.impl.ide` is left out: it held only a usage statistics listener. Phantom bundles this build instead of the
IntelliJ plugin.
