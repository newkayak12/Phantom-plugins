// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.grpc

import com.intellij.psi.PsiErrorElement
import com.intellij.psi.TokenType
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class ProtoParserTest : BasePlatformTestCase() {
  private fun parse(text: String): ProtoFile = myFixture.configureByText("api.proto", text) as ProtoFile

  private fun errors(file: ProtoFile) = PsiTreeUtil.findChildrenOfType(file, PsiErrorElement::class.java).map { it.errorDescription }

  fun testParsesAProto3FileWithoutErrors() {
    val file = parse(
      """
      syntax = "proto3";
      package shop.v1;

      import "google/protobuf/timestamp.proto";
      import public "common.proto";
      option java_package = "com.example.shop";
      option (my.ext) = { a: 1 b: "x" };

      // An order.
      message Order {
        reserved 2, 15, 9 to 11;
        reserved "foo";
        string id = 1 [deprecated = true];
        repeated Item items = 3;
        map<string, Item> by_sku = 4;
        optional .shop.v1.Order.Status status = 5;
        oneof payment {
          string card = 6;
          Cash cash = 7;
        }
        enum Status {
          option allow_alias = true;
          UNKNOWN = 0;
          NEGATIVE = -1;
        }
        message Item { int64 count = 1; double price = 2; }
        /* empty */ ;
      }

      service OrderService {
        option (svc) = "x";
        rpc Get(GetRequest) returns (Order);
        rpc Watch(stream GetRequest) returns (stream Order) {
          option idempotency_level = NO_SIDE_EFFECTS;
        }
      }

      extend google.protobuf.MessageOptions { string label = 50001; }
      """.trimIndent(),
    )
    assertEquals(emptyList<String>(), errors(file))
    checkDeclarations(file)
  }

  private fun checkDeclarations(file: ProtoFile) {
    assertEquals("shop.v1", file.packageName)
    assertEquals(listOf("google/protobuf/timestamp.proto", "common.proto"), file.imports.map { it.path })
    assertEquals(listOf("Order", "OrderService"), file.definitions.map { it.name })
    val order = file.definitions[0]
    assertEquals(listOf("id", "items", "by_sku", "status", "payment", "Status", "Item"), order.members.map { it.name })
    assertEquals(listOf("card", "cash"), order.members[4].members.map { it.name })
    assertEquals(listOf("UNKNOWN", "NEGATIVE"), order.members[5].members.map { it.name })
    assertEquals("shop.v1.Order.Item", (order.members[6] as ProtoTypeDefinition).qualifiedName)
    assertEquals(listOf("Get", "Watch"), file.definitions[1].members.map { it.name })
    val refs = PsiTreeUtil.findChildrenOfType(file, ProtoTypeRef::class.java).map { it.referenceName }
    assertEquals(
      listOf(
        "Item", "Item", ".shop.v1.Order.Status", "Cash", "GetRequest", "Order", "GetRequest", "Order",
        "google.protobuf.MessageOptions",
      ),
      refs,
    )
  }

  fun testKeywordsCanBeNames() {
    val file = parse("syntax = \"proto2\";\nmessage message { optional string option = 1; required int32 service = 2; }")
    assertEquals(emptyList<String>(), errors(file))
    assertEquals(listOf("option", "service"), file.definitions[0].members.map { it.name })
  }

  fun testBrokenStatementIsReportedAndTheRestStillParses() {
    val file = parse(
      """
      message A {
        string = 1;
        int32 ok = 2;
      }
      garbage here;
      message B {}
      """.trimIndent(),
    )
    assertTrue(errors(file).isNotEmpty())
    assertEquals(listOf("A", "B"), file.definitions.map { it.name })
    assertTrue("ok" in file.definitions[0].members.map { it.name })
  }

  fun testLexerTokens() {
    val lexer = ProtoLexer()
    lexer.start("x = 0x1F 1.5e-3 'it\\'s' /* c */ // d")
    val tokens = generateSequence { lexer.tokenType?.also { lexer.advance() } }.filter { it != TokenType.WHITE_SPACE }.toList()
    assertEquals(
      listOf(
        ProtoTokens.IDENT, ProtoTokens.EQ, ProtoTokens.INT, ProtoTokens.FLOAT, ProtoTokens.STRING,
        ProtoTokens.BLOCK_COMMENT, ProtoTokens.LINE_COMMENT,
      ),
      tokens,
    )
  }
}
