# Phantom Settings Repository 변경 이력

## 0.1.1

- 두 기기가 거의 동시에 올릴 때 한쪽 설정이 조용히 덮어써지지 않는다. 버전을 확인한 원격 커밋 위에 커밋하므로, 그 사이 다른 기기가 올렸으면 push가 거부되고 다시 받아 버전을 확인한다.
- 저장소 URL에 넣은 토큰·비밀번호(`https://user:TOKEN@host/...`)를 idea.log와 오류 메시지에 `***`로 가린다. git이 stderr에 찍는 URL도 가린다.
- git이나 ssh가 출력을 연 채 멈춰도 제한 시간(120초)이 지나면 프로세스와 그 자식 프로세스를 끝내고 시간 초과 오류를 낸다.

## 0.1.0

- Phantom 저장소의 `plugins/phantom-sync`에서 옮겨 와 이 저장소에서 빌드한다. id(`dev.phantom.sync`)와 동작은 그대로다 (#39).
