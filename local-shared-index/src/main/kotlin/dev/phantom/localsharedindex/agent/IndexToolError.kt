// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.localsharedindex.agent

/** A query that cannot be answered (no such file, still indexing); its message goes back to the agent as the tool's error. */
class IndexToolError(message: String) : Exception(message)
