// Modified by Phantom contributors, 2026; see the Git history of https://github.com/newkayak12/Phantom-plugins.
package com.intellij.grazie.detection

import com.intellij.grazie.GrazieConfig
import com.intellij.grazie.jlanguage.Lang
import com.intellij.grazie.nlp.Language

fun Lang.toLanguage(): Language = Language.entries.find { it.iso == this.iso }!!

/** Note that it will return SOME dialect */
fun Language.toLang(): Lang = Lang.entries.find { it.iso == this.iso }!!

fun Language.toAvailableLang(): Lang = GrazieConfig.get().availableLanguages.find { it.iso == this.iso }!!

fun Language.toLangOrNull(): Lang? = Lang.entries.find { it.iso == this.iso }

