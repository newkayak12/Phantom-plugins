// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.terraform

import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class HclParserTest : BasePlatformTestCase() {
  private fun parse(text: String, name: String = "main.tf"): HclFile = myFixture.configureByText(name, text) as HclFile

  private fun errors(file: HclFile) = PsiTreeUtil.findChildrenOfType(file, PsiErrorElement::class.java).map { it.errorDescription }

  private fun chains(file: HclFile) =
    PsiTreeUtil.findChildrenOfType(file, HclGetAttr::class.java).filter { it.parent !is HclGetAttr }.map { it.chain?.joinToString(".") }

  fun testParsesATerraformConfigurationWithoutErrors() {
    val file = parse(
      """
      terraform {
        required_version = ">= 1.6"
        required_providers {
          aws = { source = "hashicorp/aws", version = "~> 5.0" }
        }
      }

      # A comment
      // Another comment
      /* A block
         comment */
      variable "names" {
        type    = list(string)
        default = ["a", "b"]
        validation {
          condition     = length(var.names) > 0 && !contains(var.names, "")
          error_message = "At least one name."
        }
      }

      locals {
        prefix  = "app-${'$'}{var.env}"
        tags    = merge(var.tags, { Name = local.prefix, "team" : "core" })
        by_name = { for n in var.names : n => upper(n)... if n != "x" }
        ids     = [for i, s in aws_subnet.private : s.id]
        all_ids = aws_instance.web[*].id
        first   = aws_instance.web[0].private_ip
        legacy  = aws_instance.web.0.id
        size    = var.large ? 10 : -1 * 2 % 3
        policy  = <<-EOT
          {"Version": "${'$'}{var.v}"}
        EOT
        spread  = max(var.sizes...)
        fn      = provider::aws::arn_parse(var.arn)
        greeting = "Hello, %{ if var.name != "" }${'$'}{var.name}%{ else }you%{ endif }!"
        escaped = "$${'$'}{not} %%{not}"
      }
      """.trimIndent(),
    )
    assertEquals(emptyList<String>(), errors(file))
    assertEquals(listOf("terraform", "variable", "locals"), file.blocks.map { it.type })
    val locals = file.blocks[2].attributes.map { it.name }
    assertEquals(listOf("prefix", "tags", "by_name", "ids", "all_ids", "first", "legacy", "size", "policy", "spread", "fn", "greeting", "escaped"), locals)
  }

  fun testParsesNestedAndSingleLineBlocks() {
    val file = parse(
      """
      resource "aws_instance" "web" {
        count         = 2
        ami           = data.aws_ami.ubuntu.id
        lifecycle { ignore_changes = [tags] }
        dynamic "ebs_block_device" {
          for_each = var.disks
          content {
            device_name = ebs_block_device.value.name
          }
        }
      }
      """.trimIndent(),
    )
    assertEquals(emptyList<String>(), errors(file))
    val resource = file.blocks.single()
    assertEquals(listOf("aws_instance", "web"), resource.labels.map { it.value })
    assertEquals("web", resource.name)
    assertEquals(listOf("lifecycle", "dynamic"), resource.blocks.map { it.type })
    assertEquals(listOf("data.aws_ami.ubuntu.id", "var.disks", "ebs_block_device.value.name"), chains(file))
  }

  fun testExpressionsInsideStringsAreParsed() {
    val file = parse("a = \"x-${'$'}{var.env}-${'$'}{local.n}\"\nb = \"%{for s in var.list}${'$'}{s}%{endfor}\"")
    assertEquals(emptyList<String>(), errors(file))
    assertEquals(listOf("var.env", "local.n", "var.list"), chains(file))
    assertEquals(listOf("a", "b"), file.attributes.map { it.name })
  }

  fun testObjectKeysAreNotVariables() {
    val file = parse("tags = {\n  Name = var.name\n  env: \"prod\"\n}")
    assertEquals(emptyList<String>(), errors(file))
    val variables = PsiTreeUtil.findChildrenOfType(file, HclVariable::class.java).map { it.name }
    assertEquals(listOf("var"), variables)
  }

  fun testALineBreakEndsAnAttributeButNotInsideBrackets() {
    val file = parse("a = var.x\nb = [\n  1,\n  2\n]\nc = (1 +\n 2)")
    assertEquals(emptyList<String>(), errors(file))
    assertEquals(listOf("a", "b", "c"), file.attributes.map { it.name })
  }

  fun testABrokenLineIsReportedAndTheRestStillParses() {
    val file = parse("a = \nresource \"x_y\" \"z\" {\n  b = 1 2\n  c = )\n  d = 3\n}\n")
    assertEquals(listOf("Expression expected", "Line break expected", "Expression expected"), errors(file).take(3))
    val resource = file.blocks.single()
    assertEquals("resource", resource.type)
    assertEquals(listOf("b", "c", "d"), resource.attributes.map { it.name })
  }

  fun testAnUnterminatedStringEndsAtTheLineBreak() {
    val file = parse("a = \"open\nb = 2\n")
    assertEquals(listOf("\" expected"), errors(file))
    assertEquals(listOf("a", "b"), file.attributes.map { it.name })
  }

  fun testVariableDefinitionsFileParses() {
    val file = parse("region = \"eu-west-1\"\nzones  = [\"a\", \"b\"]\n", "prod.tfvars")
    assertEquals(emptyList<String>(), errors(file))
    assertFalse(file.isConfiguration)
  }
}
