// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.terraform

import com.intellij.psi.PsiElement
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class TerraformReferencesTest : BasePlatformTestCase() {
  private fun resolveAtCaret(): PsiElement? {
    val ref = myFixture.file.findReferenceAt(myFixture.caretOffset) ?: error("no reference at caret")
    return ref.resolve()
  }

  private var count = 0

  /** Opens a new file in the directory of `path`, with the caret where `<caret>` is. */
  private fun configure(path: String, text: String) {
    val unique = path.replace(".tf", "${count++}.tf")
    myFixture.addFileToProject(unique, text.replace("<caret>", ""))
    myFixture.configureFromTempProjectFile(unique)
    myFixture.editor.caretModel.moveToOffset(text.indexOf("<caret>"))
  }

  fun testVariableLocalAndResourceResolveAcrossTheFilesOfTheDirectory() {
    myFixture.addFileToProject("env/variables.tf", "variable \"region\" {}\nlocals {\n  name = \"x\"\n}")
    myFixture.addFileToProject("other/variables.tf", "variable \"zone\" {}")
    myFixture.addFileToProject("env/web.tf", "resource \"aws_instance\" \"web\" {\n  ami = \"ami-1\"\n}")
    configure("env/main.tf", "a = var.reg<caret>ion")
    assertEquals("region", (resolveAtCaret() as HclBlock).name)
    configure("env/main.tf", "a = local.na<caret>me")
    assertEquals("name", (resolveAtCaret() as HclAttribute).name)
    configure("env/main.tf", "a = aws_instance.w<caret>eb.id")
    assertEquals("web", (resolveAtCaret() as HclBlock).name)
    configure("env/main.tf", "a = aws_instance.web.a<caret>mi")
    assertEquals("ami", (resolveAtCaret() as HclAttribute).name)
    configure("env/main.tf", "a = aws_instance.web.i<caret>d")
    assertNull(resolveAtCaret())
    configure("env/main.tf", "a = var.zo<caret>ne")
    assertNull(resolveAtCaret())
  }

  fun testDataSourceAndModuleOutputOfALocalModule() {
    myFixture.addFileToProject("modules/net/outputs.tf", "output \"vpc_id\" {\n  value = 1\n}")
    myFixture.addFileToProject("root/data.tf", "data \"aws_ami\" \"ubuntu\" {}\nmodule \"net\" {\n  source = \"../modules/net\"\n}")
    configure("root/main.tf", "a = data.aws_ami.ubu<caret>ntu.id")
    assertEquals(listOf("aws_ami", "ubuntu"), (resolveAtCaret() as HclBlock).labels.map { it.value })
    configure("root/main.tf", "a = module.n<caret>et.vpc_id")
    assertEquals("module", (resolveAtCaret() as HclBlock).type)
    configure("root/main.tf", "a = module.net.vpc<caret>_id")
    assertEquals("output", (resolveAtCaret() as HclBlock).type)
  }

  fun testUnresolvedReferencesAreReported() {
    myFixture.enableInspections(TerraformUnresolvedReferenceInspection())
    myFixture.addFileToProject("modules/net/outputs.tf", "output \"vpc_id\" {\n  value = 1\n}")
    myFixture.addFileToProject("root/vars.tf", "variable \"known\" {}\nmodule \"net\" {\n  source = \"../modules/net\"\n}\nmodule \"remote\" {\n  source = \"hashicorp/x/aws\"\n}")
    val text = """
      locals {
        a = var.known
        b = var.<warning descr="Cannot find the declaration of var.missing">missing</warning>
        c = local.<warning descr="Cannot find the declaration of local.nope">nope</warning>
        d = aws_instance.<warning descr="Cannot find the declaration of aws_instance.web">web</warning>.id
        e = data.aws_ami.<warning descr="Cannot find the declaration of data.aws_ami.x">x</warning>.id
        f = module.net.<warning descr="Cannot find the declaration of module.net.subnet">subnet</warning>
        g = module.remote.anything
        h = module.<warning descr="Cannot find the declaration of module.gone">gone</warning>
        i = [for my_item in var.known : my_item.id]
        j = path.module
        k = each.value.name
        l = count.index
        m = var.known.field
      }
      resource "x_y" "z" {
        dynamic "disk_cfg" {
          for_each = var.known
          content { size = disk_cfg.value }
        }
      }
      moved {
        from = old_thing.gone
        to   = x_y.z
      }
    """.trimIndent()
    myFixture.addFileToProject("root/main.tf", text)
    myFixture.configureFromTempProjectFile("root/main.tf")
    myFixture.checkHighlighting(true, false, false)
  }

  fun testVariableDefinitionFilesAreNotChecked() {
    myFixture.enableInspections(TerraformUnresolvedReferenceInspection())
    myFixture.configureByText("prod.tfvars", "region = var.nope")
    myFixture.checkHighlighting(true, false, false)
  }

  fun testFindUsagesAndRenameOfAVariable() {
    myFixture.addFileToProject("m/other.tf", "output \"r\" {\n  value = var.region\n}")
    configure("m/variables.tf", "variable \"reg<caret>ion\" {}\nlocals {\n  a = var.region\n  b = \"x-\" \n}")
    val target = myFixture.elementAtCaret as HclBlock
    assertEquals(2, myFixture.findUsages(target).size)
    myFixture.renameElement(target, "location")
    myFixture.checkResult("m/variables0.tf", "variable \"location\" {}\nlocals {\n  a = var.location\n  b = \"x-\" \n}", false)
    myFixture.checkResult("m/other.tf", "output \"r\" {\n  value = var.location\n}", false)
  }

  fun testRenameOfALocalValue() {
    configure("l/main.tf", "locals {\n  na<caret>me = 1\n}\nx = local.name + local.name")
    myFixture.renameElement(myFixture.elementAtCaret, "label")
    myFixture.checkResult("l/main0.tf", "locals {\n  label = 1\n}\nx = local.label + local.label", false)
  }
}
