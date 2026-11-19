# Phantom Theme

`dev.phantom.theme` · [English](./README.md)

Phantom의 기본 모양인 Phantom Dark와 Phantom Light입니다. 거의 검은 프레임 위에 숯색 패널을 두고, 선택·탭·캐럿·포커스에 앱 아이콘의 호박색을 쓰며, 에디터 색은 조금 더 따뜻합니다. 둘 다 플랫폼의 Islands 테마를 바탕으로 합니다. 색만 바꾸고 실행되는 코드는 없습니다.

Phantom에 번들됩니다. 0.1.0 전에는 Phantom 저장소(`plugins/phantom-theme`)에서 빌드했습니다.

테마 파일은 Phantom 체크아웃의 Islands 테마에서 `python3 -I theme/tools/generate-themes.py [<Phantom 체크아웃>]`(기본 `../Phantom-IDEA`)로 다시 만듭니다. 바꾸는 값은 `tools/generate-themes.py`와 `tools/PhantomDarkScheme.overrides.xml`에 있습니다.
