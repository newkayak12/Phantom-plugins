# Support for Terraform

`dev.phantom.terraform` · [English](./README.md)

Terraform 설정 파일(`.tf`)과 변수 정의 파일(`.tfvars`) 편집을 지원합니다.

- 문자열, 숫자, 주석, 블록 타입, 속성 이름을 강조하고, 문자열 안의 `${...}`와 `%{...}` 식도 강조합니다. 잘못된 줄은 문법
  오류로 표시하고 파일의 나머지는 그대로 해석합니다.
- 서식 정리: `terraform fmt`처럼 블록 본문, 객체, 튜플을 두 칸씩 들여 쓰고 연속된 한 줄 속성의 `=`를 맞춥니다. 문자열,
  heredoc, 주석은 쓴 그대로 둡니다.
- 블록과 속성을 구조 보기로 보여 줍니다.
- `var.`, `local.`, `module.`, `data.`, 리소스 주소(`aws_instance.web`)와 선언 블록에 적은 속성(`aws_instance.web.ami`)에서
  선언으로 이동합니다. Terraform이 모듈을 읽는 방식대로 같은 디렉터리의 모든 `.tf` 파일에서 찾습니다. 모듈의 `source`가
  로컬 경로이면 `module.name.output`에서 `output` 블록으로 이동합니다.
- 변수, local, 모듈, 리소스, data source, output의 사용처 찾기와 이름 바꾸기.
- 모듈 안에 선언이 없는 주소와 로컬 모듈이 선언하지 않은 output을 경고합니다. `for` 식과 `dynamic` 블록의 반복 이름,
  `moved`와 `removed` 블록 안의 주소는 경고하지 않습니다.
- `#` 줄 주석, 블록 주석, 괄호 짝 맞추기.

프로젝트의 Terraform 파일만 읽고 파일을 열기 전에는 아무것도 하지 않습니다. 시작할 때 하는 일도, 네트워크 접근도,
provider나 registry 다운로드도 없으며 `terraform`을 실행하지 않습니다.

아직 없는 것: provider 스키마로 블록 타입과 속성 자동 완성, provider만 계산하는 속성(`id` 등), registry나 git source 모듈,
`.tf.json` 파일, heredoc 안의 식. JetBrains Terraform and HCL 플러그인과 함께 설치할 수 없습니다. 두 플러그인 모두 `.tf`
파일 타입을 등록합니다.

Terraform은 HashiCorp의 상표이며, 이 플러그인은 HashiCorp와 관계가 없고 보증받지 않았습니다.
