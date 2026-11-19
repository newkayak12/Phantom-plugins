package com.phantom.mermaid.api

@JsModule("mermaid/dist/mermaid.core.mjs")
internal external object RawMermaidModule {
  val default: MermaidModule
}
