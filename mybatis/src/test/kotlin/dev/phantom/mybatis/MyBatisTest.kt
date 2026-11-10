// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.mybatis

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlTag
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

class MyBatisTest : LightJavaCodeInsightFixtureTestCase() {
  override fun getProjectDescriptor() = RealJdk

  override fun setUp() {
    super.setUp()
    for (name in listOf("Select", "Insert", "Update", "Delete")) {
      myFixture.addClass("package org.apache.ibatis.annotations; public @interface $name { String[] value(); }")
    }
    myFixture.addClass("package org.apache.ibatis.annotations; public @interface Param { String value(); }")
    myFixture.addClass("package org.apache.ibatis.annotations; public @interface Mapper {}")
    myFixture.addClass("""
      package app;
      public class Address { private String city; public String getCity() { return city; } }
    """.trimIndent())
    myFixture.addClass("""
      package app;
      public class User {
        private Long id; private String name; private Address address;
        public Long getId() { return id; } public void setId(Long id) { this.id = id; }
        public String getName() { return name; } public void setName(String name) { this.name = name; }
        public Address getAddress() { return address; }
      }
    """.trimIndent())
    myFixture.addClass("""
      package app;
      import java.util.List;
      import org.apache.ibatis.annotations.*;
      public interface UserMapper {
        User findById(Long id);
        List<User> findAll(@Param("name") String name, @Param("ids") List<Long> ids);
        int insertUser(User user);
        void deleteAll();
        @Select("select * from users where id = #{id}") User annotated(Long id);
        default User viaDefault() { return null; }
      }
    """.trimIndent())
  }

  private fun addMapper(body: String) = myFixture.addFileToProject("app/UserMapper.xml", """
    <?xml version="1.0" encoding="UTF-8"?>
    <mapper namespace="app.UserMapper">
    $body
    </mapper>
  """.trimIndent())

  private val statements = """
    <resultMap id="user" type="app.User"><id property="id" column="id"/><result property="name" column="name"/></resultMap>
    <sql id="cols">id, name</sql>
    <select id="findById" resultMap="user">select <include refid="cols"/> from users where id = #{id}</select>
    <select id="findAll" resultType="app.User">select * from users</select>
    <insert id="insertUser">insert into users(name) values (#{name})</insert>
    <delete id="deleteAll">delete from users</delete>
  """

  fun testIndexFindsMapperXml() {
    addMapper(statements)
    val mapper = myFixture.findClass("app.UserMapper")
    assertEquals(listOf("UserMapper.xml"), MyBatis.mapperFiles(mapper).map { it.name })
    val method = mapper.findMethodsByName("findById", false).single()
    assertEquals(listOf("select"), MyBatis.statementsFor(method).map { it.name })
  }

  fun testGuttersBothWays() {
    addMapper(statements)
    myFixture.configureFromExistingVirtualFile(myFixture.findClass("app.UserMapper").containingFile.virtualFile)
    val java = gutterTargets()
    assertTrue(java.toString(), "UserMapper.xml:mapper" in java)
    assertTrue(java.toString(), "UserMapper.xml:select" in java)
    myFixture.configureFromExistingVirtualFile(myFixture.findFileInTempDir("app/UserMapper.xml"))
    val xml = gutterTargets()
    assertTrue(xml.toString(), "UserMapper" in xml && "findById" in xml && "deleteAll" in xml)
  }

  private fun gutterTargets(): List<String> = myFixture.findAllGutters().flatMap { g ->
    val info = (g as? LineMarkerInfo.LineMarkerGutterIconRenderer<*>)?.lineMarkerInfo as? RelatedItemLineMarkerInfo<*> ?: return@flatMap emptyList()
    info.createGotoRelatedItems().mapNotNull {
      when (val e = it.element) {
        is XmlTag -> e.containingFile.name + ":" + e.name
        is PsiNamedElement -> e.name
        else -> null
      }
    }
  }

  fun testReferencesResolve() {
    val file = addMapper(statements)
    myFixture.configureFromExistingVirtualFile(file.virtualFile)
    fun resolveAt(marker: String): Any? {
      val offset = file.text.indexOf(marker) + marker.indexOf('"') + 1
      return file.findReferenceAt(offset)?.resolve()
    }
    assertEquals("findById", (resolveAt("\"findById\"") as PsiMethod).name)
    assertEquals("user", (resolveAt("resultMap=\"user\"") as XmlAttributeValue).value)
    assertEquals("cols", (resolveAt("refid=\"cols\"") as XmlAttributeValue).value)
    assertEquals("name", (resolveAt("\"name\" column") as PsiNamedElement).name)
  }

  fun testRenameMethodRenamesStatementId() {
    val file = addMapper(statements)
    val method = myFixture.findClass("app.UserMapper").findMethodsByName("findById", false).single()
    myFixture.renameElement(method, "findOne")
    assertTrue(file.text, file.text.contains("<select id=\"findOne\""))
  }

  fun testXmlErrors() {
    myFixture.enableInspections(MapperXmlInspection())
    myFixture.configureByText("UserMapper.xml", """
      <?xml version="1.0" encoding="UTF-8"?>
      <mapper namespace="app.UserMapper">
      <select id="<error descr="'UserMapper' has no method 'nope'">nope</error>">select 1</select>
      <select id="<error descr="Duplicate id 'findById' in this mapper">findById</error>" resultMap="<error descr="No resultMap 'missing'">missing</error>">select 1</select>
      <select id="<error descr="Duplicate id 'findById' in this mapper">findById</error>">select <include refid="<error descr="No sql fragment 'gone'">gone</error>"/></select>
      </mapper>
    """.trimIndent())
    myFixture.checkHighlighting(false, false, false)
  }

  fun testMissingStatementAndFix() {
    myFixture.enableInspections(MapperMethodInspection())
    val xml = addMapper("<select id=\"findById\" resultType=\"app.User\">select 1</select>")
    myFixture.configureFromExistingVirtualFile(myFixture.findClass("app.UserMapper").containingFile.virtualFile)
    val problems = myFixture.doHighlighting().filter { it.description?.contains("no statement") == true }.map { it.text }.sorted()
    assertEquals(listOf("deleteAll", "findAll", "insertUser"), problems)
    myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("findAll"))
    myFixture.launchAction(myFixture.findSingleIntention("Add <select id=\"findAll\">"))
    assertTrue(xml.text, xml.text.contains("<select id=\"findAll\" resultType=\"app.User\">"))
    myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("insertUser"))
    myFixture.launchAction(myFixture.findSingleIntention("Add <insert id=\"insertUser\">"))
    assertTrue(xml.text, xml.text.contains("<insert id=\"insertUser\">"))
  }

  fun testParameterCompletion() {
    addMapper("""
      <select id="findAll" resultType="app.User">select * from users where name = #{}</select>
    """)
    myFixture.configureFromExistingVirtualFile(myFixture.findFileInTempDir("app/UserMapper.xml"))
    myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("#{") + 2)
    myFixture.completeBasic()
    assertSameElements(myFixture.lookupElementStrings!!, "name", "ids", "param1", "param2")
  }

  fun testBeanPropertyCompletion() {
    addMapper("""
      <insert id="insertUser">insert into users(name) values (#{address.})</insert>
    """)
    myFixture.configureFromExistingVirtualFile(myFixture.findFileInTempDir("app/UserMapper.xml"))
    myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("#{address.") + "#{address.".length)
    myFixture.completeBasic()
    assertEquals(listOf("city"), myFixture.lookupElementStrings)
  }

  fun testForeachItemCompletion() {
    addMapper("""
      <select id="findAll" resultType="app.User">select * from users where id in
        <foreach collection="ids" item="uid" open="(" close=")" separator=",">#{u}</foreach></select>
    """)
    myFixture.configureFromExistingVirtualFile(myFixture.findFileInTempDir("app/UserMapper.xml"))
    myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("#{u") + 3)
    myFixture.completeBasic()
    assertTrue(myFixture.file.text, myFixture.file.text.contains("#{uid}"))
  }

  fun testSqlPieces() {
    val file = addMapper("""
      <select id="findAll" resultType="app.User">select * from users
        <where><if test="name != null">and name = #{name}</if></where>
        <foreach collection="ids" item="i" open="and id in (" close=")" separator=",">#{i}</foreach>
        and x &lt; 3 <![CDATA[ and y > 2 ]]>
      </select>
    """)
    val tag = MyBatis.statements(file).single()
    val sql = MapperSql.text(MapperSql.places(tag)!!).replace(Regex("\\s+"), " ").trim()
    assertEquals("select * from users WHERE and name = ? and id in (?) and x &lt; 3 and y > 2", sql)
  }

  fun testAnnotationSqlPieces() {
    val method = myFixture.findClass("app.UserMapper").findMethodsByName("annotated", false).single()
    val annotation = method.getAnnotation("org.apache.ibatis.annotations.Select")!!
    assertEquals("select * from users where id = ?", MapperSql.text(MapperSql.places(annotation)!!))
  }

  fun testSqlIsInjectedIntoStatements() {
    val file = addMapper("""
      <select id="findAll" resultType="app.User">select id from users <where>name = #{name}</where></select>
    """)
    myFixture.configureFromExistingVirtualFile(file.virtualFile)
    val host = com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(file, com.intellij.psi.xml.XmlText::class.java).first { it.text.contains("select id") }
    val injected = com.intellij.lang.injection.InjectedLanguageManager.getInstance(project).getInjectedPsiFiles(host)?.map { it.first as com.intellij.psi.PsiFile }
    assertNotNull("SQL injected", injected)
    assertEquals("SQL", injected!!.single().language.id)
    assertEquals("select id from users  WHERE name = ?", injected.single().text)
  }

  fun testGoToImplementationFromCallSite() {
    addMapper(statements)
    myFixture.configureByText("UserService.java", """
      package app;
      class UserService { UserMapper mapper; User get() { return mapper.find<caret>ById(1L); } }
    """.trimIndent())
    val method = myFixture.elementAtCaret as PsiMethod
    val targets = com.intellij.psi.search.searches.DefinitionsScopedSearch.search(method).findAll()
    assertEquals(listOf("select"), targets.map { (it as XmlTag).name })
    val mapper = myFixture.findClass("app.UserMapper")
    assertEquals(listOf("mapper"), com.intellij.psi.search.searches.DefinitionsScopedSearch.search(mapper).findAll().map { (it as XmlTag).name })
  }

  private fun resolveIn(file: com.intellij.psi.PsiFile, marker: String, at: Int): String? {
    val offset = file.text.indexOf(marker) + at
    val e = file.findReferenceAt(offset)?.resolve()
            ?: MapperXmlGotoDeclarationHandler().getGotoDeclarationTargets(file.findElementAt(offset), offset, null)?.singleOrNull()
            ?: return null
    return when (e) {
      is XmlAttributeValue -> "attr:" + e.value
      is com.intellij.psi.PsiParameter -> "param:" + e.name
      is PsiNamedElement -> e.javaClass.simpleName.removePrefix("Psi").removeSuffix("Impl") + ":" + e.name
      else -> e.toString()
    }
  }

  fun testPlaceholderJumps() {
    val file = addMapper("""
      <select id="findAll" resultType="app.User">select * from users
        <where>
          <if test="name != null and name.length() > 0">name = #{name, jdbcType=VARCHAR}</if>
          <if test="ids != null">id in <foreach collection="ids" item="uid" separator=",">#{uid}</foreach></if>
          and x = #{param1}
        </where>
      </select>
      <insert id="insertUser">insert into users(name, city) values (#{name}, #{address.city})</insert>
    """)
    assertEquals("param:name", resolveIn(file, "#{name, jdbc", 3))
    assertEquals("param:name", resolveIn(file, "test=\"name !=", 7))
    assertEquals("param:name", resolveIn(file, "and name.length", 5))
    assertNull("method call", resolveIn(file, "name.length()", 6))
    assertEquals("param:ids", resolveIn(file, "collection=\"ids", 13))
    assertEquals("attr:uid", resolveIn(file, "#{uid}", 3))
    assertEquals("param:name", resolveIn(file, "#{param1}", 3))
    assertEquals("Field:name", resolveIn(file, "values (#{name}", 10))
    assertEquals("Field:address", resolveIn(file, "#{address.city}", 3))
    assertEquals("Field:city", resolveIn(file, "#{address.city}", 11))
  }

  fun testAnnotationPlaceholderJumpsAndUsages() {
    val mapper = myFixture.findClass("app.UserMapper")
    val file = mapper.containingFile
    assertEquals("param:id", resolveIn(file, "#{id}", 3))
    val usages = myFixture.findUsages(mapper.findMethodsByName("annotated", false).single().parameterList.parameters.single())
    assertEquals(1, usages.size)
  }
}
