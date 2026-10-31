# Ranger Client 데모

Ranger 2.9.0 스택(Admin · KMS · PDP)을 docker compose로 띄우고, Spring Boot 웹 UI에서 SDK 기능을 하나씩 실행해 보는 데모.
화면의 모든 동작은 **실행한 SDK 코드**, **실제 HTTP 요청(시도별, 재시도·장애 조치 포함, 요청·응답 본문)**, **결과 또는 매핑된 예외**를 함께 보여준다.
설계는 [DESIGN.md](DESIGN.md) 참고.

## 준비물

- Docker (Compose v2), 메모리 약 5 GB, 디스크 약 3 GB
- JDK/Maven은 필요 없다. SDK와 앱은 이미지 빌드 중에 컨테이너 안에서 빌드한다.

## 빠른 시작

```bash
cd ranger-client-demo
docker compose up -d --build --wait     # 처음에는 이미지 다운로드·빌드 포함 5–10분
open http://localhost:8080              # 브라우저로 접속
docker compose down                     # 정지. 모든 데이터가 함께 지워진다
```

- 앱이 뜨면 시드 데이터(사용자·그룹·`demo_hive` 서비스·정책·존·태그·KMS 키)를 SDK로 넣는다. 대시보드의 **시드 데이터** 상태가 `DONE`이 되면 준비 완료.
- 상태는 컨테이너 안에만 있다(볼륨 없음). `down` 후 다시 `up` 하면 Admin·KMS 설치와 DB가 함께 처음부터 시작된다.
- 기동 확인: `scripts/smoke.sh` (앱 페이지, PDP 판정, KMS 키를 점검하고 실패 시 exit 1).

## 화면 구성

```
┌─ 메뉴 ──────────────────────────────────────────────────────── ? 도움말 ─┐
│ 본문: 입력 폼과 결과                              │ 도움말 (따로 스크롤)   │
│  실행한 SDK 코드 · 결과 JSON 또는 예외            │ 개념, 사용법,          │
│                                                   │ 따라 해 보기,          │
│ 이 화면에서 클라이언트가 보낸 요청                │ 같은 일을 하는 Java    │
│  # 클라이언트 메서드 경로 시도 상태 시간 (본문)   │ 코드                   │
└───────────────────────────────────────────────────┴────────────────────────┘
```

- **본문**: 각 메뉴의 입력 폼과 결과. 버튼을 누르면 결과 패널에 실행한 SDK 코드와 결과(JSON)가 나온다. 실패하면 SDK 예외 타입(`RangerNotFoundException` 등), HTTP 상태, Ranger가 보낸 오류 메시지, `X-Request-Id`가 나온다.
- **요청 표**: 그 화면(또는 그 동작)을 처리하면서 SDK가 보낸 HTTP 요청. 경로를 누르면 전체 URI와 요청·응답 본문이 펼쳐진다. 시도가 2 이상이면 자동 재시도, 빨간 행은 실패한 요청. 요청 본문의 비밀번호는 가려진다.
- **도움말**: 처음 쓰는 사람을 위한 설명. 본문과 따로 스크롤되고, 위쪽 "? 도움말" 버튼이나 × 로 숨긴다(브라우저에 기억). 화면 폭이 좁으면 본문 아래로 내려간다.
- **코드 표시와 편집기**: JSON·YAML·Java는 Monaco Editor로 보여주고 편집한다. webjar로 포함하므로 인터넷 연결이 없어도 된다.

## 메뉴

| 메뉴 | 확인할 수 있는 것 | SDK API |
|---|---|---|
| 대시보드 | starter가 만든 클라이언트 빈 3개의 URL·노드 상태·인증 방식, 서버 기능 감지, 시드 상태와 재실행 | `capabilities().supports`, `nodeAvailability()` |
| 서비스 정의 / 서비스 | servicedef 조회, 서비스 생성·수정·삭제·upsert·연결 테스트 | `admin.serviceDefs()`, `admin.services()` |
| 정책 | `PolicyFilter` 검색, 상세, 생성·수정(버전 확인)·`apply`·`serverApply`, 항목 추가·주체 제거, export/import | `admin.policies()` |
| 역할 / 사용자·그룹 | 역할 CRUD·grant/revoke, 사용자·그룹·멤버십 | `admin.roles()`, `admin.users()`, `admin.groups()` |
| 보안 영역 / 태그 | 존 v2 생성·부분 수정, 태그 정의·태그·리소스 매핑 | `admin.zones()`, `admin.tags()` |
| PDP 권한 확인 | `authorize` / `authorizeMulti` / `permissions`, 마스킹·행 필터 결과, 판정 캐시 비교 | `RangerPdpClient` |
| KMS | 키 생성·rollover·메타데이터·버전, EEK 생성 → 복호화 → 재암호화 | `RangerKmsClient` |
| Policy as Code | YAML 편집 → plan(diff) → apply, 서비스 정책 export | `SpecLoader`, `PolicyAsCode` |
| 장애 실험 | 죽은 노드로의 장애 조치와 cooldown, 1 ms timeout 재시도(같은 `X-Request-Id`), 예외 카탈로그(404·409·400·401·403·Unsupported·Transport), 낙관적 잠금 충돌 | `RetryPolicy`, 클라이언트 빌더 |
| 요청 기록 | 앱 전체의 최근 HTTP 시도(헬스 체크·시더 포함)와 본문, 최근 SDK 호출, Micrometer `ranger.client.requests` / `ranger.client.retries` 요약 | `RequestInterceptor` |

## 포트와 계정

| 서비스 | 주소 (호스트) | 계정 / 인증 |
|---|---|---|
| 데모 앱 | http://localhost:8080 | 없음 |
| Ranger Admin UI·REST | http://localhost:6080 | `admin` / `rangerR0cks!`, `keyadmin` / `rangerR0cks!` |
| Ranger KMS | http://localhost:9292 | Hadoop simple (`?user.name=keyadmin`) |
| Ranger PDP | http://localhost:6500 | 헤더 `X-Forwarded-User: demo-app` (모든 서비스의 위임 사용자) |
| 시드 사용자 | — | `alice`(analyst), `bob`(engineer), `mallory` / `Demo1234!` |

포트·비밀번호는 `ranger-client-demo/.env`에서 바꾼다. 셸 환경변수가 `.env`보다 우선한다(예: `APP_PORT=18090 docker compose up -d --wait`).
값은 Apache Ranger 공식 docker 예제의 공개 기본값이며, 로컬 테스트 전용이다.

## 앱만 따로 실행하기

앱 코드를 고치면서 확인할 때는 스택만 compose로 띄우고 앱은 호스트에서 실행한다.

```bash
cd ranger-client-demo && docker compose up -d --wait ranger-admin ranger-kms ranger-pdp
cd ../java && mvn install -DskipTests                 # SDK를 로컬 저장소에 설치
cd ../ranger-client-demo/app
RANGER_ADMIN_PASSWORD='rangerR0cks!' mvn spring-boot:run -Dspring-boot.run.profiles=local
```

`local` 프로파일은 `localhost:6080/6500/9292`를 쓴다. 컨테이너의 앱과 함께 띄우려면 `-Dspring-boot.run.arguments=--server.port=18080`처럼 포트를 바꾼다.
SDK를 고친 뒤 컨테이너 앱에 반영하려면 `docker compose up -d --build demo-app`.

## 문제 해결

| 증상 | 확인 |
|---|---|
| `up --wait`가 오래 걸림 | Admin 최초 설치(스키마 생성)에 2–3분, KMS 설치에 1–2분 걸린다. `docker compose ps`로 상태 확인 |
| 서비스가 unhealthy / exited | `docker compose logs ranger-admin` (또는 `ranger-kms`, `ranger-pdp`, `demo-app`). KMS 상세 로그는 `docker compose exec ranger-kms ls /var/log/ranger/kms` |
| KMS·PDP 이미지 빌드 중 다운로드 실패 | 바이너리를 `dlcdn.apache.org`에서 받고 실패하면 `archive.apache.org`(느림)로 재시도하며 sha512를 검증한다. 프록시 환경이면 Docker 빌드에 프록시를 설정 |
| Dashboard의 Demo data가 `FAILED` | 실패한 단계와 예외가 표시된다. 서버가 늦게 뜬 경우 **Run seed again**으로 재실행(모든 단계가 upsert) |
| KMS 키 작업이 403 | KMS 플러그인이 `dev_kms` 정책을 받기 전(기동 후 약 30초)일 수 있다. 잠시 후 재시도 |
| 포트 충돌 | `.env`의 `*_PORT`를 바꾸거나 셸에서 `APP_PORT=...`를 지정 |
| Apple Silicon | 모든 이미지가 arm64를 지원한다(`apache/ranger*`, `apache/ranger-base`, `eclipse-temurin`). 별도 설정 불필요 |
| 처음부터 다시 | `docker compose down && docker compose up -d --wait` |
