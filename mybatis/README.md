# Phantom MyBatis

`dev.phantom.mybatis` · [한국어](./KOR.md)

MyBatis mapper support for Java, covering what MyBatisX does day to day.

- **Navigation:** a gutter icon on the mapper interface and each method jumps to the mapper XML and its statement; on `<mapper>` and each `<select>` / `<insert>` / `<update>` / `<delete>` it jumps back to the interface and method.
- **Go to Implementation:** Cmd+Opt+B (Ctrl+Alt+B) on a mapper method, at its declaration or at any call such as `userMapper.findById(id)` in a service, opens its XML statement; on the mapper interface, the mapper XML.
- **Jumps inside statements:** Cmd+click (Ctrl+click) on a name in `#{user.address.city}` / `${...}`, in `<if test>` / `<when test>`, `<foreach collection>` and `<bind value>`, and in `@Select` / `@Insert` / `@Update` / `@Delete` strings goes to the method parameter (by `@Param` name or `paramN`), the field or getter of each property, or the `<foreach item>` / `<bind>` that declares it.
- **References:** `namespace` and `resultType` / `parameterType` / `type` / `javaType` / `ofType` to classes, a statement `id` to its method (renaming the method renames the id), `resultMap` / `extends` / `<include refid>` to the `<resultMap>` / `<sql>` they name (also `other.Mapper.id`), and `property` in a result map to the field or setter. All complete with Ctrl+Space; a statement `id` offers the methods that have no statement yet.
- **Errors:** a statement with no method in the interface, duplicate ids, `resultMap` and `refid` that name nothing, and a `namespace` with no interface.
- **Missing statements:** a mapper method with no XML statement and no `@Select` / `@Insert` / `@Update` / `@Delete` (calling it throws `BindingException`). The quick fix adds the statement to the mapper XML: `<insert>`, `<update>` or `<delete>` by the method name, otherwise `<select>` with the `resultType` of the return type.
- **`#{}` parameters:** typing `#{` or `${` opens completion with the method's parameters (`@Param` names, `param1`…), the properties of a single bean parameter, `a.b` paths, and `<foreach item>` / `<bind>` variables.
- **SQL as MyBatis builds it:** with Phantom Database Console installed, statements in mapper XML and in `@Select` / `@Insert` / `@Update` / `@Delete` get its SQL highlighting, table and column completion and navigation. The dynamic tags read as MyBatis assembles them: `<where>` adds `WHERE` (a leading `AND` / `OR` stays valid), `<set>` adds `SET` and drops the last comma, `<trim>` adds its prefix and suffix and drops its overrides, `<foreach>` adds `open` / `close`, `<include refid>` brings in the `<sql>` fragment. `#{}` and `${}` read as `?`.
- **Syntax errors:** a statement that reads whole (no `${}`, no `<choose>`, every `<include>` found) gets the same syntax errors as a `.sql` file, such as `select id, from users`. Needs Phantom Database Console 0.25.2.
- **Run:** a run icon next to each `<select>` / `<insert>` / `<update>` / `<delete>` and each `@Select`… runs the statement in Phantom Database Console. It asks for the values of `#{name}` (as `:name`, `#{user.name}` as `:user_name`) and `${...}`. `<if>` parts are all kept and `<choose>` takes its first branch, so edit the values or the SQL in the dialog when a branch should go.
- **Reformat Code:** in a mapper XML file also formats the SQL of statements and `<sql>` fragments that are plain text (or one CDATA section), with the SQL code style, one indent inside the tag. Statements with dynamic tags are left as written.

Phantom suggests this plugin when a project depends on MyBatis, MyBatis-Spring, the MyBatis Spring Boot starter or MyBatis-Plus. In other projects it does nothing.

Not covered yet: the MyBatis Generator style code generation from tables, and SQL from method names.
