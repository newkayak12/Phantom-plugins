# Phantom MyBatis

`dev.phantom.mybatis` · [English](./README.md)

Java용 MyBatis 매퍼 지원입니다. MyBatisX로 매일 쓰는 기능을 다룹니다.

- **이동:** 매퍼 인터페이스와 각 메서드 옆 거터 아이콘으로 매퍼 XML과 해당 statement로 갑니다. `<mapper>`와 각 `<select>` / `<insert>` / `<update>` / `<delete>`에서는 인터페이스와 메서드로 돌아갑니다.
- **구현으로 이동:** 매퍼 메서드에서 Cmd+Opt+B(Ctrl+Alt+B)를 누르면 XML statement가 열립니다. 선언에서도, 서비스의 `userMapper.findById(id)` 같은 호출에서도 됩니다. 매퍼 인터페이스에서는 매퍼 XML이 열립니다.
- **statement 안에서 이동:** `#{user.address.city}` / `${...}`, `<if test>` / `<when test>`, `<foreach collection>`, `<bind value>`, `@Select` / `@Insert` / `@Update` / `@Delete` 문자열 안의 이름을 Cmd+클릭(Ctrl+클릭)하면 메서드 파라미터(`@Param` 이름이나 `paramN`), 각 프로퍼티의 필드나 getter, 그 이름을 선언한 `<foreach item>` / `<bind>`로 이동합니다.
- **참조:** `namespace`와 `resultType` / `parameterType` / `type` / `javaType` / `ofType`은 클래스로, statement `id`는 메서드로 연결됩니다(메서드 이름을 바꾸면 id도 바뀝니다). `resultMap` / `extends` / `<include refid>`는 해당 `<resultMap>` / `<sql>`로(`other.Mapper.id`도), result map의 `property`는 필드나 setter로 연결됩니다. 모두 Ctrl+Space로 자동완성되며, statement `id`에는 아직 statement가 없는 메서드를 제안합니다.
- **오류:** 인터페이스에 없는 메서드의 statement, 중복 id, 없는 `resultMap`과 `refid`, 인터페이스가 없는 `namespace`를 알려 줍니다.
- **빠진 statement:** XML statement도 `@Select` / `@Insert` / `@Update` / `@Delete`도 없는 매퍼 메서드를 알려 줍니다(호출하면 `BindingException`). 빠른 수정은 매퍼 XML에 statement를 추가합니다. 메서드 이름에 따라 `<insert>`, `<update>`, `<delete>`를 쓰고, 그 밖에는 반환 타입을 `resultType`으로 한 `<select>`를 씁니다.
- **`#{}` 파라미터:** `#{`나 `${`를 입력하면 메서드 파라미터(`@Param` 이름, `param1`…), 단일 빈 파라미터의 프로퍼티, `a.b` 경로, `<foreach item>` / `<bind>` 변수를 자동완성합니다.
- **SQL:** Phantom Database Console이 설치되어 있으면 매퍼 XML과 `@Select` / `@Insert` / `@Update` / `@Delete`의 SQL에 하이라이팅, 테이블·컬럼 자동완성, 이동이 적용됩니다. `#{}`와 `${}`는 `?`로 읽고, `<where>`, `<set>`, `<trim>`, `<foreach>`는 MyBatis가 붙이는 내용을 더해서 읽습니다.

프로젝트가 MyBatis, MyBatis-Spring, MyBatis Spring Boot starter, MyBatis-Plus를 쓰면 Phantom이 이 플러그인 설치를 제안합니다. 그 밖의 프로젝트에서는 아무것도 하지 않습니다.

아직 없는 기능: 테이블에서 코드를 만드는 MyBatis Generator 방식의 생성, 메서드 이름으로 SQL 만들기.
