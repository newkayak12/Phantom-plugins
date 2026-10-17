# Phantom Spring

`dev.phantom.spring` · [English](./README.md)

Java와 Kotlin 프로젝트를 위한 Spring 지원입니다.

- **빈 이동:** 주입받는 자리의 여백 아이콘을 누르면 그 자리를 채울 수 있는 빈으로 가고, 빈의 아이콘을 누르면 그 빈을 주입받는 곳으로 돌아갑니다. 생성자 주입, 필드 주입, Kotlin 주 생성자 주입을 지원합니다. 스테레오타입, 직접 만든 `@UseCase` 같은 메타 애너테이션, `@Bean` 메서드도 인식합니다.
- **자동 주입 검사:** 주입할 타입의 빈이 프로젝트에 없거나, 여러 개인데 고를 근거가 없으면 알려 줍니다. 빠른 수정으로 `@Qualifier`나 `@Primary`를 붙일 수 있습니다. 선택적 주입, 컬렉션, 테스트 소스는 검사하지 않습니다.
- **잘못된 "사용 안 함" 표시 없음:** Spring이 관리하는 클래스와 메서드(빈, `@Bean`, 이벤트 리스너 등)를 쓰지 않는 코드로 표시하지 않습니다.
- **Endpoints 툴 윈도우:** Spring MVC 핸들러를 모듈별로 보여 줍니다. 예: `GET /api/v1/menu/{id}  FindMenuController.findMenu`
  - `@Controller` 클래스와 컨트롤러가 구현한 인터페이스의 `@RequestMapping`, `@GetMapping` 계열 애너테이션을 읽습니다.
  - 상수와 문자열 템플릿으로 만든 경로도 실제 경로로 풀어 줍니다.
  - 메서드, 경로, 컨트롤러 이름으로 거를 수 있습니다. Enter나 더블클릭으로 핸들러를 열고, 우클릭 메뉴로 URL을 복사합니다.

프로젝트가 `spring-context`나 `spring-web`에 의존하면 Phantom이 이 플러그인 설치를 제안합니다. Spring을 쓰지 않는 프로젝트에서는 아무 동작도 하지 않습니다.

아직 없는 것: WebFlux 함수형 라우트, Spring Security, Spring Data 리포지토리.
