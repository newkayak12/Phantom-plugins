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
- **SQL:** with Phantom Database Console installed, statements in mapper XML and in `@Select` / `@Insert` / `@Update` / `@Delete` get its SQL highlighting, table and column completion and navigation. `#{}` and `${}` read as `?`, and `<where>`, `<set>`, `<trim>` and `<foreach>` add what MyBatis adds.

Phantom suggests this plugin when a project depends on MyBatis, MyBatis-Spring, the MyBatis Spring Boot starter or MyBatis-Plus. In other projects it does nothing.

Not covered yet: the MyBatis Generator style code generation from tables, and SQL from method names.
