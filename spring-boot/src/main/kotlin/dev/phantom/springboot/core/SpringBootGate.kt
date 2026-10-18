// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.springboot.core

import com.intellij.java.library.JavaLibraryUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.module.Module
import com.intellij.psi.JavaPsiFacade

/** Every Phantom Spring Boot extension calls this first; modules without Spring Boot pay one cached lookup. */
object SpringBootGate {
  private const val SPRING_BOOT_MAVEN = "org.springframework.boot:spring-boot"
  private const val SPRING_APPLICATION = "org.springframework.boot.SpringApplication"

  fun isBootModule(module: Module): Boolean {
    if (module.isDisposed) return false
    if (JavaLibraryUtil.hasLibraryJar(module, SPRING_BOOT_MAVEN)) return true
    if (JavaLibraryUtil.hasLibraryClass(module, SPRING_APPLICATION)) return true
    // Light test fixtures declare SpringApplication as a source stub instead of a library.
    return ApplicationManager.getApplication().isUnitTestMode &&
           JavaPsiFacade.getInstance(module.project).findClass(SPRING_APPLICATION, module.getModuleWithDependenciesAndLibrariesScope(false)) != null
  }
}

object BootConfigFiles {
  private val NAME = Regex("""(application|bootstrap)([-.][\w.-]*)?\.(ya?ml|properties)""")

  fun isConfigFile(fileName: String): Boolean = NAME.matches(fileName)

  /** An `application*` / `bootstrap*` file, or one that `spring.config.import` pulls in. */
  fun isConfigFile(file: com.intellij.psi.PsiFile): Boolean {
    if (isConfigFile(file.name)) return true
    val name = file.name
    if (!name.endsWith(".yml") && !name.endsWith(".yaml") && !name.endsWith(".properties")) return false
    val virtualFile = file.originalFile.virtualFile ?: return false
    return virtualFile in BootConfigImports.importedFiles(file.project)
  }
}
