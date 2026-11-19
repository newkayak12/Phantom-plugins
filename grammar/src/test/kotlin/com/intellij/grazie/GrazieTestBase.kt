// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.grazie

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.grazie.grammar.LanguageToolChecker
import com.intellij.grazie.ide.inspection.grammar.GrazieInspection
import com.intellij.grazie.jlanguage.Lang
import com.intellij.grazie.spellcheck.GrazieCheckers
import com.intellij.grazie.spellcheck.GrazieSpellCheckingInspection
import com.intellij.grazie.text.TextChecker
import com.intellij.grazie.utils.TextStyleDomain
import com.intellij.grazie.utils.TextStyleDomain.AIPrompt
import com.intellij.grazie.utils.TextStyleDomain.CodeComment
import com.intellij.grazie.utils.TextStyleDomain.CodeDocumentation
import com.intellij.grazie.utils.TextStyleDomain.Commit
import com.intellij.lang.Language
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

abstract class GrazieTestBase : BasePlatformTestCase() {
  companion object {
    val inspectionTools: Array<LocalInspectionTool> by lazy {
      arrayOf(GrazieInspection(), GrazieInspection.Grammar(), GrazieInspection.Style(), GrazieSpellCheckingInspection())
    }

    /**
     * To speed up test execution, only English is enabled by default.
     *
     * Please use [enableProofreadingFor] if a test requires a specific language
     */
    val enabledLanguages = setOf(Lang.AMERICAN_ENGLISH)
    val enabledRules = setOf("LanguageTool.EN.COMMA_WHICH", "LanguageTool.EN.UPPERCASE_SENTENCE_START", "LanguageTool.DE.MANNSTUNDE")

    @JvmStatic
    fun maskSaxParserFactory(disposable: Disposable) {
      val saxParserKey = "javax.xml.parsers.SAXParserFactory"
      val oldSaxParserFactory = System.setProperty(saxParserKey, "com.sun.org.apache.xerces.internal.jaxp.SAXParserFactoryImpl")
      Disposer.register(disposable) {
        if (oldSaxParserFactory != null) System.setProperty(saxParserKey, oldSaxParserFactory)
        else System.clearProperty(saxParserKey)
      }
    }

    @JvmStatic
    fun installTestChecker(disposable: Disposable) {
      val newExtensions = TextChecker.allCheckers().map { if (it is LanguageToolChecker) LanguageToolChecker.TestChecker() else it }
      ExtensionTestUtil.maskExtensions(ExtensionPointName("com.intellij.grazie.textChecker"), newExtensions, disposable)
    }
  }

  protected open val additionalEnabledRules: Set<String> = emptySet()

  protected open val additionalEnabledContextLanguages: Set<Language> = emptySet()

  // phantom: testData sits in the plugin folder, which is where Gradle runs the tests
  override fun getTestDataPath(): String = java.io.File("testData").absolutePath

  override fun setUp() {
    super.setUp()
    myFixture.enableInspections(*inspectionTools)

    maskSaxParserFactory(testRootDisposable)
    installTestChecker(testRootDisposable)
    enableProofreadingFor(enabledLanguages)
  }

  override fun tearDown() {
    try {
      GrazieConfig.update { GrazieConfig.State() }
      service<GrazieCheckers>().awaitConfiguration()
    }
    catch (e: Throwable) {
      addSuppressedException(e)
    }
    finally {
      super.tearDown()
    }
  }

  @JvmOverloads
  protected fun enableProofreadingFor(
    languages: Set<Lang>,
    domainEnabledRules: MutableMap<TextStyleDomain, Set<String>> = HashMap(),
    domainDisabledRules: MutableMap<TextStyleDomain, Set<String>> = HashMap(),
  ) {
    val enabledLanguages = languages + GrazieConfig.get().enabledLanguages

    GrazieConfig.update { state ->
      val checkingContext = state.checkingContext.copy(
        isCheckInStringLiteralsEnabled = true,
        isCheckInCommentsEnabled = true,
        isCheckInDocumentationEnabled = true,
        enabledLanguages = additionalEnabledContextLanguages.map { it.id }.toSet(),
      )
      val domains = setOf(Commit, AIPrompt, CodeDocumentation, CodeComment)
      if (domainEnabledRules.isEmpty()) {
        domains.forEach { domainEnabledRules[it] = enabledRules + additionalEnabledRules }
      }
      state.copy(
        enabledLanguages = enabledLanguages,
        userEnabledRules = enabledRules + additionalEnabledRules,
        checkingContext = checkingContext,
        domainEnabledRules = domainEnabledRules,
        domainDisabledRules = domainDisabledRules,
      )
    }

    service<GrazieCheckers>().awaitConfiguration()
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
  }

  protected open fun runHighlightTestForFile(file: String) {
    myFixture.configureByFile(file)
    myFixture.checkHighlighting(true, false, false)
  }
}
