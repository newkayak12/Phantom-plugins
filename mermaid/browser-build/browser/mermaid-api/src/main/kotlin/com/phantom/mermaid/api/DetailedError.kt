package com.phantom.mermaid.api

external interface DetailedError {
  @JsName("str")
  val string: String
  val hash: Any
  val error: Any?
  val message: String?
}
