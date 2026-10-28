# Phantom Icon Pack

`dev.phantom.iconpack` · [English](./README.md)

파일 이름과 확장자에 따라 파일·폴더 아이콘에 색을 입힙니다. 아이콘은 Philipp Kief의 [Material Icon Theme](https://github.com/material-extensions/vscode-material-icon-theme)(MIT, `src/main/resources/icons/material/LICENSE.md`)에서 가져왔습니다.

- **파일과 폴더**: 파일 이름, 그다음 확장자로 테마 아이콘을 고릅니다. source, test, resource 루트는 폴더 색이 따로 있습니다. 화면에 보이는 파일의 아이콘만 불러옵니다.
- **종류별 클래스 아이콘**: Java와 Kotlin 클래스는 모양으로 구분되는 Material 아이콘을 씁니다. class(원), abstract·sealed class(점선 원), interface(둥근 사각형), enum(육각형), annotation(@), record·data class(카드), exception(경고 삼각형, Java), object(마름모, Kotlin)이고, Kotlin 쪽은 구석에 Kotlin 표시가 붙습니다. Structure 뷰, 자동 완성 등 클래스가 나오는 곳에서 쓰며, Java 클래스의 static/final 표시와 두 언어의 접근 제한자 표시는 그대로 둡니다.
- **파일도 클래스 종류로**: Java 파일, 그리고 클래스 하나만 선언한 Kotlin 파일은 파일이 나오는 곳(Project 뷰, 내비게이션 바, 에디터 탭, Recent Files, Search Everywhere)에서도 그 클래스의 종류 아이콘을 보여 줍니다. 그 밖의 Kotlin 파일은 Kotlin 로고, 클래스가 없는 Java 파일은 Java 찻잔입니다.
- VCS 화면에서 지워졌거나 아직 받지 않은 파일도 아이콘을 유지합니다.

플러그인을 끄면 플랫폼 아이콘으로 돌아갑니다.

0.3.0 전에는 Phantom에 번들되어 있었습니다(`phantom-icons`, id `dev.phantom.icons`). 쓰는 Phantom에 아직 번들되어 있다면, 이 플러그인을 설치한 뒤 Settings → Plugins에서 **Phantom File Icons**를 꺼 주세요.

**0.3.2**: 클래스를 색이 아니라 모양으로 구분하고, Kotlin도 Java와 같이 갑니다. 종류 아이콘을 새로 그렸고(원, 둥근 사각형, 육각형, @, 카드, 삼각형, 마름모), Kotlin 클래스에는 Kotlin 표시가 붙으며, 파일도 0.3.1의 종류별 색 찻잔이나 Kotlin 로고 대신 클래스 종류 아이콘으로 보입니다.

**0.3.1**: Java 파일의 찻잔이 그 파일이 선언한 클래스 종류의 색(구조 뷰의 종류 아이콘과 같은 색)을 띱니다. 그래서 Project 뷰, 에디터 탭, 내비게이션 바에서 interface, annotation, enum 등을 구분할 수 있습니다. 클래스가 여러 개면 파일 이름과 같은 클래스를 따릅니다.

**0.3.0**: Phantom 번들 밖의 플러그인이 되었고 공개 플랫폼 API만 씁니다. Java 파일은 클래스 종류 아이콘 대신 Kotlin 로고처럼 Java 찻잔을 보여 줍니다.

Material 아이콘은 `python3 -I tools/generate-material-icons.py ~/.vscode/extensions/pkief.material-icon-theme-<버전>`으로 다시 만듭니다.
