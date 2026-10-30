# Phantom Bytecode Viewer

`dev.phantom.bytecode` · [한국어](./KOR.md)

The bytecode of a compiled Java or Kotlin class in the ASM format. Select a `.java`, `.kt` or `.class` file, or put the
caret in a class, and open **View | Show Bytecode**. The Bytecode tool window follows the editor: the caret or selection
in the source highlights the matching instructions (Sync with Editor), and Show Debug Info toggles line numbers and local
variables. Source files need to be compiled first; the window offers to build when the class file is missing.

Ported from the IntelliJ Community Bytecode Viewer (`plugins/ByteCodeViewer`, Apache 2.0) with its own id, settings and
extension points, so it updates without a new Phantom build. Turn the bundled Bytecode Viewer off if your Phantom still
bundles it.
