# Phantom Tab Groups

`dev.phantom.tabs` · [English](./README.md)

웹 브라우저처럼 에디터 탭을 그룹으로 묶습니다.

- **그룹:** 탭 우클릭 → Add Tab to New Group / Add Tab to Group / Remove from Group. 그룹에 든 탭은 그룹 색으로 옅게 칠해지고, 그룹의 첫 탭에 그룹 이름이 붙습니다(`Work · Service.kt`).
- **접기:** 그룹에 든 탭 우클릭 → Collapse Group. 첫 탭만 남아 그룹 이름과 탭 수를 보여 줍니다(`▸ Work (3)`). 그 탭을 누르거나 Expand Group으로 다시 펼칩니다. 접으면 탭을 숨길 뿐이라 파일은 열려 있고, 그룹에 든 탭은 탭 개수 제한으로 닫히지 않습니다.
- **그룹 메뉴:** 같은 탭 메뉴에 Rename Group…, Color, Ungroup, Close Group이 있습니다.
- **순서:** 그룹의 탭은 붙어 있고, 탭을 끌어 그룹에 넣거나 뺍니다.
- 그룹은 에디터 분할마다 따로 있고 프로젝트와 함께 저장됩니다. 그룹을 만들기 전에는 탭 줄이 바뀌지 않습니다.

접기는 탭이 위에 한 줄로 스크롤되고 이름순 정렬이 꺼져 있을 때만 됩니다. 그 밖에는 그룹이 탭에 색과 이름만 붙입니다.

2026-10-26까지는 Phantom에 번들(`phantom-tabs`)되어 Phantom 전용 플랫폼 변경으로 탭 안에 칩을 그렸습니다. 이제 공개 플랫폼 API만 쓰는 플러그인이라 Phantom을 다시 빌드하지 않고 업데이트됩니다.
