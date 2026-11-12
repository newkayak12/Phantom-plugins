package com.phantom.mermaid.api

object Mermaid {
  val api: MermaidApi
    get() = core.api

  val core: MermaidModule
    get() = MermaidModuleManager.obtainModule()
}
