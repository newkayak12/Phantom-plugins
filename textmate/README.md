# TextMate Bundles Support

`dev.phantom.textmate` · [한국어](./KOR.md)

Highlighting, brace matching, commenting and snippets for file types no other plugin covers, from TextMate and VS Code grammars: the 23 bundled ones (`lib/bundles`) and any you add in Settings | Editor | TextMate Bundles. Markdown highlights code fences with it.

Based on the IntelliJ Community TextMate Bundles plugin (`plugins/textmate`, Apache 2.0), under Phantom's own id. `org.jetbrains.plugins.textmate` stays
as an alias and the content module names stay the IntelliJ ones, so plugins and bundled modules that depend on them
keep working. Phantom bundles this build instead of the IntelliJ plugin.

The grammars are read from this plugin's own folder wherever it is installed, not only from the IDE's `plugins/textmate-plugin`.

**0.1.1**: the plugin jar carries the licenses of the bundled grammars (`META-INF/LICENSE-textmate`: VS Code's MIT
license, and which bundles keep their own, MPL 2.0 for HashiCorp's HCL and Terraform) and lists the packaged libraries
(Joni and JCodings, MIT) in `META-INF/THIRD-PARTY-NOTICES.txt`.

**0.1.2**: ships 23 of the 64 grammars: Kotlin, SQL, Dockerfile, .env, INI, logs, Makefile, Batch, PowerShell, diff, git files,
JSP, AsciiDoc, HCL, Terraform, Bazel, CMake, JavaScript, TypeScript, CSS, SCSS, Less and Python. The IDE reads every
shipped grammar when a project opens. The ones left out are covered by a Phantom plugin (Java, Groovy, JSON, YAML,
Markdown, shell, XML, HTML) or are not used in a Java or Kotlin backend project. Add any of them back in Settings | Editor |
TextMate Bundles.
