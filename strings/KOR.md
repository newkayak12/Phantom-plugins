# Phantom Strings

`dev.phantom.strings` · [English](./README.md)

선택 영역마다, 또는 캐럿 위치의 식별자에 텍스트 변환을 적용합니다. Alt+M을 누르면 검색되는 팝업으로 열리고,
**Edit | Strings**와 에디터 오른쪽 클릭 메뉴에도 있습니다. 캐럿이 여러 개면 캐럿마다 따로 변환합니다.

- **대소문자:** Switch Case는 camelCase → snake_case → SCREAMING_SNAKE_CASE → kebab-case → PascalCase 순으로 바꾸고,
  결과가 같은 형식은 건너뜁니다. 원하는 형식이나 dot.case, 띄어쓴 단어, 대문자, 소문자로 바로 바꿀 수도 있습니다.
  단어는 대소문자가 바뀌는 곳, 약어(`HTTPServer` → `http_server`), `_ - .`, 공백에서 나눕니다. 선택이 없으면 캐럿의 식별자에 적용합니다.
- **이스케이프·인코딩**(선택 영역): Java/Kotlin 문자열, HTML/XML, URL, Base64를 양방향으로 변환합니다.
  디코딩할 수 없는 입력은 그대로 두고 힌트로 알립니다.
- **줄**(선택 영역): 정렬(자연 순서라 `a2`가 `a10`보다 앞), 역순 정렬, 뒤집기, 섞기, 중복 제거, 빈 줄 제거, 앞뒤 공백 제거.

StringManipulation 플러그인을 대체합니다. Phantom은 둘을 함께 로드하지 않습니다.
