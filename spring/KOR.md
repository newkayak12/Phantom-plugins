# Support for Spring
`dev.phantom.spring` · [English](./README.md)

Java와 Kotlin 프로젝트를 위한 Spring 지원입니다.

- **빈 이동:** 주입받는 자리의 여백 아이콘을 누르면 그 자리를 채울 수 있는 빈으로 가고, 빈의 아이콘을 누르면 그 빈을 주입받는 곳으로 돌아갑니다. 생성자 주입, 필드 주입, Kotlin 주 생성자 주입을 지원합니다. 스테레오타입, 직접 만든 `@UseCase` 같은 메타 애너테이션, `@Bean` 메서드도 인식합니다.
- **자동 주입 검사:** 주입할 타입의 빈이 프로젝트에 없거나, 여러 개인데 고를 근거가 없으면 알려 줍니다. 빠른 수정으로 `@Qualifier`나 `@Primary`를 붙일 수 있습니다. 선택적 주입, 컬렉션, 테스트 소스는 검사하지 않습니다.
- **잘못된 "사용 안 함" 표시 없음:** Spring이 관리하는 클래스와 메서드(빈, `@Bean`, 이벤트 리스너 등)를 쓰지 않는 코드로 표시하지 않습니다.
- **Endpoints:** Phantom의 Endpoints 툴 윈도우에 Spring MVC 핸들러를 모듈별로 채웁니다. 예: `GET /api/v1/menu/{id}  FindMenuController.findMenu`
  - `@Controller` 클래스와 컨트롤러가 구현한 인터페이스, 상속한 추상 기반 클래스의 `@RequestMapping`, `@GetMapping` 계열 애너테이션을 읽습니다. 클래스의 `@RequestMapping`은 합성 애너테이션을 거쳐 붙어 있어도 읽습니다.
  - 상수와 문자열 템플릿으로 만든 경로도 실제 경로로 풀어 줍니다.
  - 메서드, 경로, 컨트롤러 이름으로 거를 수 있습니다. Enter나 더블클릭으로 핸들러를 열고, 우클릭 메뉴로 URL을 복사합니다.
- **Spring Security:** `requestMatchers("…")`와 Kotlin DSL `authorize("…")`에서 프로젝트 핸들러 경로(와 `/**` 접두사)를 자동완성합니다. `@PreAuthorize` / `@PostAuthorize` / `@PreFilter` / `@PostFilter`에서 함수, 변수, `#인자`를 자동완성하고, 메서드 인자가 아닌 `#이름`은 오류로 표시합니다.
- **메시징 (Kafka, RabbitMQ, JMS):** 같은 토픽·큐·목적지를 보내는 곳과 받는 곳을 여백 아이콘으로 연결하고, 토픽 이름을 쓰는 문자열에서 이름을 자동완성합니다.
  - 보내는 곳: `KafkaTemplate.send(topic, …)`, `ProducerRecord(topic, …)`, `JmsTemplate`, `RabbitTemplate.convertAndSend`
  - 받는 곳: `@KafkaListener`, `@RabbitListener`, `@JmsListener`, Kafka나 Confluent parallel consumer의 `subscribe(…)`. 같은 메서드에서 만든 토픽 목록이나 도우미 함수가 돌려주는 목록도 읽습니다.
  - 실행할 때만 정해지는 이름(`"$TOPIC-RETRY-$n"`, 설정 값)은 따라가지 않습니다.

프로젝트가 `spring-context`나 `spring-web`에 의존하면 Phantom이 이 플러그인 설치를 제안합니다. Spring을 쓰지 않는 프로젝트에서는 아무 동작도 하지 않습니다.

- **Spring Beans** 툴 윈도우: 프로젝트의 모든 빈을 모듈과 종류(컨트롤러, 서비스, 리포지터리, 컴포넌트, 설정 클래스와 그 `@Bean` 메서드)별로 보여 줍니다. 빈을 펼치면 주입받는 빈과 각 주입 지점을 채우는 빈, 그리고 이 빈을 주입받는 곳이 나옵니다. 두 번 클릭하면 소스로 이동합니다. 창을 열 때나 Refresh를 누를 때만 계산합니다.

아직 없는 것: WebFlux 함수형 라우트, Spring Security 필터 체인 보기. Spring Data 리포지토리는 [Support for JPA](../jpa/KOR.md)에 있습니다.

**0.9.6**: 이름을 바꿨습니다: Spring Support → Phantom for Spring. Phantom으로 시작하고 지원하는 프로젝트 이름을 뒤에 둡니다. 플러그인 id와 기능은 그대로입니다.

**0.9.5**: 큰 프로젝트에서 입력하는 동안 수백 밀리초씩 멈추던 문제를 고쳤습니다. 0.9.3에서 생긴 문제입니다. 모든 빈 이름이
평범한 문자열인지 보는 검사가 편집할 때마다 애너테이션이 붙은 파일을 전부 다시 읽었는데, 이제 클래스, 애너테이션, 타입 별칭이
바뀔 때만 다시 합니다. 결과는 그대로입니다.

**0.9.4**: 입력하는 동안 CPU를 덜 씁니다. 빈 이름과 주입 지점을 채울 수 있는 빈은 메서드 본문이나 필드 초기값 안을 고치는
동안 그대로 두고, 클래스, 애너테이션, 시그니처, 상위 타입이 바뀔 때 다시 찾습니다. 결과는 그대로입니다.

**0.9.3**: 입력하는 동안 CPU를 덜 씁니다. Kafka, RabbitMQ, JMS 거터 아이콘은 자기 줄만 보고, 프로젝트 전체 검색은
아이콘을 눌렀을 때 합니다. 중복 빈 이름 검사는 검사하는 이름만 찾고, 프로젝트에 평범한 문자열이 아닌 빈 이름이 있을 때만
모든 빈을 읽습니다. 결과는 그대로입니다.

**0.9.2**: 입력하는 동안 CPU를 덜 씁니다. 설정 파일과 빌드 파일은 Java나 Kotlin 코드를 고칠 때마다가 아니라 그 파일이
바뀔 때만 다시 읽고, 거터 아이콘은 Spring 프로젝트가 아니면 더 일찍 멈춥니다. 결과는 그대로입니다.

## 고스트 모드

Phantom의 고스트 모드(레지스트리 키 `phantom.ghost`)가 켜져 있으면 이 플러그인의 검사와 거터 아이콘이 꺼진다. 색칠, 문법 오류, 완성, 이동은 그대로다.
