// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.openapi

import com.intellij.icons.AllIcons
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.microservices.endpoints.EndpointsFilter
import com.intellij.microservices.endpoints.EndpointsProvider
import com.intellij.microservices.endpoints.FrameworkPresentation
import com.intellij.microservices.endpoints.HTTP_SERVER_TYPE
import com.intellij.microservices.endpoints.presentation.HttpMethodPresentation
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.util.SimpleModificationTracker
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import org.jetbrains.yaml.psi.YAMLKeyValue
import java.util.concurrent.atomic.AtomicInteger

class OpenApiTest : LightJavaCodeInsightFixtureTestCase() {
  companion object {
    init {
      System.setProperty("idea.load.plugins.id", "dev.phantom.openapi,org.jetbrains.plugins.yaml,com.intellij.modules.json,com.intellij.java")
    }
  }

  override fun getProjectDescriptor() = RealJdk

  override fun setUp() {
    super.setUp()
    myFixture.enableInspections(OpenApiRefInspection::class.java)
  }

  private fun errors(): List<String> = myFixture.doHighlighting(HighlightSeverity.ERROR).filter { it.inspectionToolId?.startsWith("PhantomOpenApi") == true }
    .mapNotNull { it.description }.sorted()

  private val petstore = """
    openapi: 3.0.3
    info:
      title: Petstore
      version: 1.0.0
    servers:
      - url: https://example.com/api
    paths:
      /pets/{petId}:
        get:
          responses:
            '200':
              content:
                application/json:
                  schema:
                    ${'$'}ref: '#/components/schemas/Pet'
            '404':
              ${'$'}ref: 'common.yaml#/components/responses/NotFound'
            '500':
              ${'$'}ref: 'common.yaml#/components/responses/Nope'
            '503':
              ${'$'}ref: 'missing.yaml'
            '418':
              ${'$'}ref: 'https://example.com/teapot.yaml'
        delete:
          responses:
            '204':
              description: gone
    components:
      schemas:
        Pet:
          type: object
          properties:
            owner:
              ${'$'}ref: '#/components/schemas/Owner'
            tags:
              type: array
              items:
                ${'$'}ref: "#/components/schemas/Tag"
        Tag:
          type: string
  """.trimIndent()

  fun testUnresolvedRefs() {
    myFixture.addFileToProject("common.yaml", "components:\n  responses:\n    NotFound:\n      description: not found\n")
    myFixture.configureByText("petstore.yaml", petstore)
    assertEquals(listOf(
      "Cannot resolve '#/components/schemas/Owner'",
      "Cannot resolve 'common.yaml#/components/responses/Nope'",
      "Cannot resolve 'missing.yaml'",
    ), errors())
  }

  fun testNavigationAndCompletion() {
    myFixture.addFileToProject("common.yaml", "components:\n  responses:\n    NotFound:\n      description: not found\n")
    myFixture.configureByText("petstore.yaml", petstore.replace("'#/components/schemas/Pet'", "'#/components/schemas/P<caret>et'"))
    val target = myFixture.getReferenceAtCaretPositionWithAssertion().resolve()
    assertEquals("Pet", (target as YAMLKeyValue).keyText)

    myFixture.configureByText("petstore2.yaml", petstore.replace("'#/components/schemas/Owner'", "'#/components/schemas/<caret>'"))
    val variants = myFixture.completeBasic()?.map { it.lookupString }.orEmpty()
    assertContainsElements(variants, "#/components/schemas/Pet", "#/components/schemas/Tag")
  }

  fun testJsonSpecAndPlainFiles() {
    myFixture.configureByText("api.json", """
      {
        "openapi": "3.1.0",
        "paths": {},
        "components": {
          "schemas": {
            "User": { "type": "object" },
            "Team": { "type": "array", "items": { "${'$'}ref": "#/components/schemas/User" } },
            "Bad": { "${'$'}ref": "#/components/schemas/Nobody" }
          }
        }
      }
    """.trimIndent())
    assertEquals(listOf("Cannot resolve '#/components/schemas/Nobody'"), errors())

    // a JSON schema or another YAML file with ${'$'}ref is not a spec: left alone
    myFixture.configureByText("schema.json", """{ "definitions": {}, "items": { "${'$'}ref": "#/definitions/Missing" } }""")
    assertEmpty(errors())
    myFixture.configureByText("application.yaml", "spring:\n  application:\n    name: app\nx:\n  ${'$'}ref: '#/nothing'\n")
    assertEmpty(errors())
  }

  fun testPathMatching() {
    assertTrue(OpenApiSpecs.pathsMatch("/users/{id}", "/users/{userId}/"))
    assertTrue(OpenApiSpecs.pathsMatch("/users/{id}", "/api/users/{id}"))
    assertTrue(OpenApiSpecs.pathsMatch("/api/users/{id}", "/users/{id}"))
    assertFalse(OpenApiSpecs.pathsMatch("/users/{id}", "/apiusers/{id}"))
    assertFalse(OpenApiSpecs.pathsMatch("/users", "/users/{id}"))
    assertFalse(OpenApiSpecs.pathsMatch("/", "/users"))
    // a parameter alone or a parameter in the extra prefix is not a base path
    assertFalse(OpenApiSpecs.pathsMatch("/{id}", "/users/{id}"))
    assertFalse(OpenApiSpecs.pathsMatch("/users/{id}", "/{tenant}/users/{id}"))
    assertEquals(listOf("/pets"), CodeEndpoints.preferExact(listOf("/api/pets", "/pets"), "/pets") { it })
    assertEquals(listOf("/api/pets"), CodeEndpoints.preferExact(listOf("/api/pets"), "/pets") { it })
  }

  fun testRenameChangesOnlyTheLastPointerSegment() {
    myFixture.addFileToProject("common.yaml", "components:\n  responses:\n    NotFound:\n      description: not found\n")
    myFixture.configureByText("petstore.yaml", petstore.replace("'#/components/schemas/Pet'", "'#/components/schemas/P<caret>et'"))
    myFixture.renameElementAtCaret("Big~Pet")
    val text = myFixture.editor.document.text
    assertTrue(text, text.contains("'#/components/schemas/Big~0Pet'"))
    assertTrue(text, text.contains("Big~Pet:"))
  }

  /** A spec split into files: `${'$'}ref` in the files the spec points to resolve and are checked too. */
  fun testRefsInSplitSpecFiles() {
    myFixture.addFileToProject("api.yaml", """
      openapi: 3.0.3
      info:
        title: split
        version: "1"
      paths:
        /pets:
          ${'$'}ref: 'paths/pets.yaml'
    """.trimIndent())
    myFixture.addFileToProject("paths/pets.yaml", "get:\n  responses:\n    '200':\n      ${'$'}ref: '../schemas/pet.yaml#/Pet'\n")
    val pet = myFixture.addFileToProject("schemas/pet.yaml", "Pet:\n  properties:\n    owner:\n      ${'$'}ref: '#/Owner'\n    tag:\n      ${'$'}ref: '#/Tag'\nTag:\n  type: string\n")
    myFixture.configureFromExistingVirtualFile(pet.virtualFile)
    assertEquals(listOf("Cannot resolve '#/Owner'"), errors())
  }

  /** A stand-in for Spring MVC: one GET handler on `/api/pets/{id}`. */
  private class FakeProvider(private val tracker: ModificationTracker = ModificationTracker.NEVER_CHANGED, private val handler: () -> PsiElement?) :
    EndpointsProvider<String, String> {
    val searches = AtomicInteger()
    override val endpointType = HTTP_SERVER_TYPE
    override val presentation = FrameworkPresentation("Fake", "Fake", AllIcons.Nodes.Method)
    override fun getStatus(project: Project) = EndpointsProvider.Status.HAS_ENDPOINTS
    override fun getEndpointGroups(project: Project, filter: EndpointsFilter): Iterable<String> {
      searches.incrementAndGet()
      return listOf("group")
    }
    override fun getEndpoints(group: String): Iterable<String> = listOf("/api/pets/{id}")
    override fun isValidEndpoint(group: String, endpoint: String) = true
    override fun getEndpointPresentation(group: String, endpoint: String): ItemPresentation =
      HttpMethodPresentation(endpoint, listOf("GET"), "PetController.find", AllIcons.Nodes.Method)
    override fun getModificationTracker(project: Project): ModificationTracker = tracker
    override fun getNavigationElement(group: String, endpoint: String): PsiElement? = handler()
  }

  fun testGuttersBetweenSpecAndHandler() {
    myFixture.addClass("""
      package app;
      public class PetController {
          @Deprecated
          public String find(long id) { return ""; }
      }
    """.trimIndent())
    val handler = { JavaPsiFacade.getInstance(project).findClass("app.PetController", myFixture.module.moduleScope)?.findMethodsByName("find", false)?.firstOrNull() as PsiMethod? }
    ExtensionTestUtil.maskExtensions(EndpointsProvider.EP_NAME, listOf(FakeProvider(handler = handler)), testRootDisposable)
    myFixture.addFileToProject("common.yaml", "components:\n  responses:\n    NotFound:\n      description: not found\n")
    myFixture.configureByText("petstore.yaml", petstore)
    val specGutters = myFixture.findAllGutters().map { it.tooltipText }
    assertEquals(listOf("Go to the handler of GET /pets/{petId}"), specGutters)

    val controller = myFixture.findFileInTempDir("app/PetController.java")
    myFixture.configureFromExistingVirtualFile(controller)
    assertEquals(listOf("Go to GET /pets/{petId} in petstore.yaml"), myFixture.findAllGutters().map { it.tooltipText }.filter { it?.startsWith("Go to") == true })
  }

  /** The endpoint list follows the providers' trackers, not every PSI change: a YAML keystroke does not search again. */
  fun testEndpointsFollowProviderTrackers() {
    val tracker = SimpleModificationTracker()
    val provider = FakeProvider(tracker) { null }
    ExtensionTestUtil.maskExtensions(EndpointsProvider.EP_NAME, listOf(provider), testRootDisposable)
    CodeEndpoints.all(project)
    val searches = provider.searches.get()
    myFixture.configureByText("notes.yaml", "a: <caret>1\n")
    myFixture.type("2")
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    CodeEndpoints.all(project)
    assertEquals(searches, provider.searches.get())
    tracker.incModificationCount()
    CodeEndpoints.all(project)
    assertEquals(searches + 1, provider.searches.get())
  }

  /** Handlers carry mapping annotations: gutters of a file without any never ask the providers. */
  fun testFileWithoutAnnotationsDoesNotAskProviders() {
    val provider = FakeProvider { null }
    ExtensionTestUtil.maskExtensions(EndpointsProvider.EP_NAME, listOf(provider), testRootDisposable)
    myFixture.addFileToProject("petstore.yaml", petstore)
    myFixture.configureByText("Plain.java", "class Plain { void run() {} }")
    myFixture.findAllGutters()
    assertEquals(0, provider.searches.get())
  }

  /** The probe the real project check used: a spec in a module's resource root with a `${'$'}ref` to a missing schema. */
  fun testSpecInAResourceRoot() {
    val resources = myFixture.tempDirFixture.findOrCreateDir("module/src/main/resources")
    com.intellij.testFramework.PsiTestUtil.addSourceRoot(module, resources, org.jetbrains.jps.model.java.JavaResourceRootType.RESOURCE)
    try {
      val file = myFixture.addFileToProject("module/src/main/resources/probe-openapi.yaml", """
        openapi: 3.0.1
        info:
          title: probe
          version: "1"
        paths:
          /x:
            get:
              responses:
                "200":
                  description: ok
                  content:
                    application/json:
                      schema:
                        ${'$'}ref: "#/components/schemas/Missing"
        components:
          schemas:
            Present:
              type: object
      """.trimIndent())
      myFixture.configureFromExistingVirtualFile(file.virtualFile)
      assertEquals(1, errors().size)
    }
    finally {
      com.intellij.testFramework.PsiTestUtil.removeSourceRoot(module, resources)
    }
  }

  private fun specNames(): List<String> = OpenApiSpecs.specFiles(project).map { it.name }.sorted()

  private val tinySpec = "openapi: 3.0.3\ninfo:\n  title: t\n  version: '1'\npaths: {}\n"

  /** A file changed on disk while not open has no PSI event; the VFS listener still drops the spec list. */
  fun testSpecFilesSeeExternalChange() {
    val file = myFixture.tempDirFixture.createFile("external.yaml", "name: not a spec\n")
    assertDoesntContain(specNames(), "external.yaml")
    WriteAction.run<Throwable> { VfsUtil.saveText(file, tinySpec) }
    assertContainsElements(specNames(), "external.yaml")
    WriteAction.run<Throwable> { VfsUtil.saveText(file, "name: not a spec again\n") }
    assertDoesntContain(specNames(), "external.yaml")
  }

  fun testSpecFilesSeeUnsavedEdit() {
    myFixture.configureByText("edited.yaml", "<caret>openapi: 3.0.3\ninfo:\n  title: t\n  version: '1'\npaths: {}\n")
    assertContainsElements(specNames(), "edited.yaml")
    myFixture.type("x-")
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    assertDoesntContain(specNames(), "edited.yaml")
  }

  fun testSpecFilesSeeCreatedAndDeletedFiles() {
    assertDoesntContain(specNames(), "created.json")
    val file = myFixture.tempDirFixture.createFile("created.json", "{ \"openapi\": \"3.1.0\", \"paths\": {} }")
    assertContainsElements(specNames(), "created.json")
    WriteAction.run<Throwable> { file.delete(this) }
    assertDoesntContain(specNames(), "created.json")
  }

  /** The point of the narrower dependencies: typing in Java does not rescan the yaml/json files. */
  fun testJavaEditDoesNotRecomputeSpecFiles() {
    myFixture.addFileToProject("api.yaml", tinySpec)
    myFixture.configureByText("Foo.java", "class Foo { <caret> }")
    assertContainsElements(specNames(), "api.yaml")
    val before = OpenApiSpecs.specFilesComputations.get()
    myFixture.type("int x;")
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    assertContainsElements(specNames(), "api.yaml")
    assertEquals(before, OpenApiSpecs.specFilesComputations.get())
  }

  fun testGhostModeTurnsOffRefChecksAndGuttersAndKeepsNavigation() {
    ghostMode(true)
    myFixture.addClass("package app; public class PetController { public String find(long id) { return \"\"; } }")
    val handler = { JavaPsiFacade.getInstance(project).findClass("app.PetController", myFixture.module.moduleScope)?.findMethodsByName("find", false)?.firstOrNull() as PsiMethod? }
    ExtensionTestUtil.maskExtensions(EndpointsProvider.EP_NAME, listOf(FakeProvider(handler = handler)), testRootDisposable)
    myFixture.addFileToProject("common.yaml", "components:\n  responses:\n    NotFound:\n      description: not found\n")
    myFixture.configureByText("petstore.yaml", petstore.replace("'#/components/schemas/Pet'", "'#/components/schemas/P<caret>et'"))
    assertEmpty(errors())
    assertEmpty(myFixture.findAllGutters().filter { it.tooltipText?.startsWith("Go to") == true })
    assertEquals("Pet", (myFixture.getReferenceAtCaretPositionWithAssertion().resolve() as YAMLKeyValue).keyText)
  }

  // Phantom registers phantom.ghost; the IntelliJ test platform does not, so a system property, which RegistryValue reads before
  // the bundle, makes the key resolvable for setValue.
  private fun ghostMode(on: Boolean) {
    System.setProperty(GhostMode.KEY, "false")
    Disposer.register(testRootDisposable) { System.clearProperty(GhostMode.KEY) }
    Registry.get(GhostMode.KEY).setValue(on, testRootDisposable)
  }
}
