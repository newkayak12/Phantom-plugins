@file:ApiStatus.Experimental

package com.intellij.repository.search.completion.statistics

import com.intellij.openapi.util.Key
import org.jetbrains.annotations.ApiStatus

// phantom-idea: no JetBrains usage statistics. The collector extension and lookup usage descriptor that reported dependency
// completion to the "completion" FUS group are gone; the key stays (in this file, so the class name stays the same),
// as the bundled Gradle completion still marks its lookup elements with it.
val BT_COMPLETION_IS_AUTO_POPUP: Key<Boolean> = Key.create("BT_COMPLETION_IS_AUTO_POPUP")
