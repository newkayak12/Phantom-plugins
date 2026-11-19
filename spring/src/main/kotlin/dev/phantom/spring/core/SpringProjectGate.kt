// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.spring.core

import com.intellij.java.library.JavaLibraryUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope

/**
 * Every Spring Support extension calls this first and returns immediately when it is false,
 * so projects without Spring pay nothing beyond this check.
 */
object SpringProjectGate {
  fun isSpringProject(project: Project): Boolean {
    if (project.isDefault || project.isDisposed) return false
    // Both lookups are cached by JavaLibraryUtil and invalidated only by root changes.
    if (JavaLibraryUtil.hasLibraryJar(project, SpringNames.SPRING_CONTEXT_MAVEN)) return true
    if (JavaLibraryUtil.hasLibraryClass(project, SpringNames.COMPONENT)) return true
    // Light test fixtures declare the Spring annotations as source stubs instead of a library.
    return ApplicationManager.getApplication().isUnitTestMode &&
           JavaPsiFacade.getInstance(project).findClass(SpringNames.COMPONENT, GlobalSearchScope.allScope(project)) != null
  }
}
