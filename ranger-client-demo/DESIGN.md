# Ranger Client Demo — 설계

Ranger 2.9.0 스택(Admin · KMS · PDP)을 docker compose로 띄우고, Spring Boot 웹 UI에서 `ranger-sdk`의 기능을 직접 실행해 보는 데모.
목표는 **`git clone` → `docker compose up` → 브라우저** 세 단계로 모든 SDK 기능을 확인하는 것이다.

## 1. 목표와 범위

| 목표 | 내용 |
|---|---|
| 바로 실행 | 로컬에 JDK/Maven이 없어도 Docker만 있으면 된다. SDK와 앱은 컨테이너 안에서 빌드한다. |
| SDK 기능 노출 | Admin(servicedef·service·policy·role·user/group·zone·tag), PDP, KMS, Policy-as-Code, 장애 조치·재시도·예외 매핑, 기능 감지, 헬스·메트릭·트레이싱을 각각 화면으로 확인. |
| "무엇이 호출됐나"를 보여줌 | 화면의 모든 동작에 대해 **실행한 SDK 코드**, **실제 HTTP 요청(시도별)**, **결과 또는 매핑된 예외**를 함께 보여준다. 데모의 핵심 가치. |
| 재실행 안전 | 시드 데이터는 SDK의 `apply`(upsert)와 Policy-as-Code로 넣어 몇 번을 다시 띄워도 같은 상태가 된다. |

범위 밖(1차): Kerberos(KDC 컨테이너), TLS, GDS(2.x 서버에 없음), Ranger 플러그인이 붙은 실제 Hive/HDFS. Kerberos·TLS는 나중에 compose profile로 추가할 수 있게 자리만 잡아 둔다(§9).

## 2. 저장소 배치

```
ranger-client-demo/
  DESIGN.md                 # 이 문서
  README.md                 # 실행 방법 (3단계 + 트러블슈팅)
  .env                      # 이미지 버전, 포트, 데모용 비밀번호 (공개 가능한 기본값만)
  docker-compose.yml        # 기본 스택
  docker-compose.otel.yml   # (선택) Jaeger 추가: docker compose -f ... -f ... up
  docker/
    fetch-release.sh        # Apache 배포본 다운로드(dlcdn → archive 순) + sha512 검증
    kms/Dockerfile          # apache/ranger-base(Java 8) + ranger-2.9.0-kms.tar.gz
    kms/install.properties
    kms/entrypoint.sh       # 최초 기동 시 setup.sh(rangerkms DB 생성 포함), 플러그인 basic 인증 설정
    pdp/Dockerfile          # apache/ranger-base(Java 8) + ranger-2.9.0-pdp.tar.gz
    pdp/ranger-pdp-site.xml
    pdp/entrypoint.sh
  app/                      # Spring Boot 앱 (독립 Maven 프로젝트)
    Dockerfile              # 멀티 스테이지: SDK reactor 빌드 → 앱 빌드 → JRE 21 런타임
    pom.xml
    src/main/java/io/datadynamics/ranger/demo/...
    src/main/resources/templates/...   # Thymeleaf
    src/main/resources/seed/           # 시드 YAML (Policy-as-Code 형식)
  scripts/
    smoke.sh                # 기동 후 주요 엔드포인트 점검 (CI/수동)
```

- 앱은 `java/` reactor에 모듈로 넣지 않는다. SDK 빌드(`mvn install`)가 Spring Boot 4 앱과 데모 의존성까지 끌어오지 않게 하고, 데모는 **배포된 SDK를 쓰는 일반 사용자 관점**을 유지하기 위해서다. 앱 `pom.xml`은 `ranger-sdk-spring-boot-starter:${ranger-sdk.version}`(기본 `0.2.0`)에 의존한다.
- `scripts/publish-public.sh`로 공개 저장소에 그대로 나가므로 `ranger-client-demo/` 안에는 Ranger 공식 docker 예제와 같은 공개 기본 비밀번호(`rangerR0cks!`) 외의 비밀값을 두지 않는다.

## 3. Docker 구성

### 3.1 이미지 출처

| 서비스 | 이미지 | 비고 |
|---|---|---|
| `ranger-db` | `apache/ranger-db:2.9.0` (Postgres) | Docker Hub 공식. `ranger` DB/`rangeradmin` 계정 생성 |
| `ranger-solr` | `apache/ranger-solr:2.9.0` | 감사 로그 저장소. Admin 기동에 필요 |
| `ranger-admin` | `apache/ranger:2.9.0` | Docker Hub 공식. 포트 6080 |
| `ranger-kms` | **로컬 빌드** `apache/ranger-base`(Java 8 변형) + `ranger-2.9.0-kms.tar.gz` | KMS 공식 이미지는 Hub에 없음. Apache 배포 바이너리를 빌드 시 받아 sha512 검증(dlcdn 미러 우선, archive.apache.org는 매우 느려 예비용) |
| `ranger-pdp` | **로컬 빌드** `apache/ranger-base`(Java 8 변형) + `ranger-2.9.0-pdp.tar.gz` | 위와 동일. 포트 6500. 2.9.0 PDP는 JAXB가 필요해 Java 11+ 베이스에서 기동 실패 |
| `demo-app` | **로컬 빌드** `ranger-client-demo/app/Dockerfile` | 포트 8080 |
| `jaeger` (선택) | `jaegertracing/jaeger` | `docker-compose.otel.yml`에서만 |

KMS/PDP Dockerfile과 기동 스크립트는 Apache Ranger의 `dev-support/ranger-docker`(release-ranger-2.9.0)를 따르되, Kerberos 분기를 빼고 tarball을 `ADD <url>` + `--checksum` 또는 `curl | sha512sum -c`로 받는다. 모든 이미지가 amd64/arm64를 지원하므로 Apple Silicon에서도 동작한다(`apache/ranger-base` 멀티 아치).

### 3.2 서비스 구성과 기동 순서

```
ranger-db ──healthy──► ranger-admin ──healthy──► ranger-kms
ranger-solr ─started─►      │          └──────► ranger-pdp
                            └──────healthy─────► demo-app ◄── (kms, pdp는 started만 기다림)
```

| 서비스 | healthcheck | 포트(호스트) |
|---|---|---|
| ranger-db | `pg_isready` | 노출 안 함 |
| ranger-solr | `curl /solr/admin/info/system` | 노출 안 함 (`8983`은 필요 시 주석 해제) |
| ranger-admin | `curl -sf -u admin:… /service/public/v2/api/servicedef?pageSize=1` | `${ADMIN_PORT:-6080}` |
| ranger-kms | `curl -sf /kms/api/status` | `${KMS_PORT:-9292}` |
| ranger-pdp | `curl -sf /health/ready` | `${PDP_PORT:-6500}` |
| demo-app | `curl -sf /actuator/health/liveness` | `${APP_PORT:-8080}` |

- 앱은 Admin이 healthy가 된 뒤 뜬다. KMS/PDP는 늦게 준비돼도 앱이 먼저 떠서 해당 화면에 `DOWN`을 보여준다(헬스 인디케이터 상태 그대로). 데모로서 "서버가 아직 준비 안 됨"도 하나의 확인 대상이다.
- Admin 최초 기동(스키마 생성)은 2–3분 걸린다. README에 `docker compose up --wait` 사용을 안내한다.
- 볼륨 없음: Admin/KMS의 setup 완료 표시가 컨테이너 안에 있으므로 DB만 남기면 재생성 시 상태가 어긋난다. `docker compose down`이 곧 초기화이고, `stop`/`start`는 상태를 유지한다.
- 필요 자원: 메모리 약 5 GB, 이미지 약 2.5 GB.

### 3.3 서버 설정 포인트

**Admin**
- 기본 계정 `admin` / `rangerR0cks!`(공식 이미지 기본값, 구현 시 확인). 앱은 `RANGER_ADMIN_PASSWORD` 환경변수로 받는다.

**KMS**
- `install.properties`: Postgres(`ranger-db`), `REPOSITORY_NAME=dev_kms`, `POLICY_MGR_URL=http://ranger-admin:6080`, HTTP 9292. Kerberos/감사 관련 키도 빈 값이면 setup.sh가 거부하므로 자리표시 값을 둔다.
- Admin 2.9는 인증 없는 정책 다운로드를 거부한다("Unauthenticated access not allowed"). entrypoint가 `ranger.plugin.kms.policy.rest.client.username/password`(keyadmin)를 `ranger-kms-security.xml`에 넣는다.
- `dev_kms` 서비스와 keyadmin 정책은 공식 Admin 이미지의 `create-ranger-services.py`가 만든다.
- KMS 플러그인은 Admin의 `dev_kms` 서비스 정책을 받아 키 권한을 판단한다. `dev_kms` 서비스와 "keyadmin에 모든 키 작업 허용" 정책은 **앱 시더가 SDK로 만든다**(§5). 시더 실행 전까지 KMS 키 작업은 403 → 데모 화면이 이 상태를 설명한다.
- 앱은 `hadoop-simple` 인증(`user.name=keyadmin`)으로 붙는다.

**PDP**
- `ranger-pdp-site.xml`: 인증은 `header`만 켠다(`X-Forwarded-User`). JWT(외부 JWKS 필요)·Kerberos는 끈다.
- `ranger.pdp.service.*.delegation.users=demo-app` — 플레이그라운드가 임의 사용자(alice, bob …) 대신 판정을 요청하려면 호출자가 위임 사용자여야 한다. 위임 사용자가 아닐 때의 403도 "예외" 화면에서 재현한다.
- 정책 폴링 주기 5초(`ranger.authz.default.policy.pollIntervalMs`)로 "정책 수정 → 판정 변화"를 바로 본다.
- `ranger.authz.default.use.rangerGroups=true`: 그룹을 Ranger 사용자 저장소에서 풀어서 사용자 이름만으로 판정한다.
- `ranger.authz.default.policy.rest.url=http://ranger-admin:6080`.

## 4. 데모 앱 아키텍처

### 4.1 기술 선택

| 항목 | 선택 | 이유 |
|---|---|---|
| 런타임 | Java 21, Spring Boot 4.1.x | starter가 Boot 4 / Java 17+ 전용 |
| UI | **Thymeleaf + htmx**(CDN 없이 webjar), 자체 CSS·JS(`static/css/demo.css`, `static/js/demo.js`) | Node 빌드 없이 Maven 하나로 끝남. 부분 갱신(htmx)으로 SPA 수준의 반응성. 화면 구성(상단 메뉴, 오른쪽 도움말 패널, 요청 표)은 Atlas Client 데모와 같다 |
| JSON·YAML·Java 보기/편집 | Monaco Editor webjar(`pre.code[data-lang]`, `textarea[data-editor]`) | 오프라인에서도 동작. Monaco를 불러오지 못하면 원래 `<pre>`/`<textarea>`를 그대로 씀 |
| SDK 연결 | `ranger-sdk-spring-boot-starter` | 클라이언트 빈·헬스·메트릭·트레이싱 자동 구성 자체가 데모 대상 |
| 추가 SDK 모듈 | `ranger-sdk-policy-as-code`, `ranger-sdk-opentelemetry` | PaC 화면, 트레이싱 |
| 관측 | Actuator, Micrometer(Prometheus 엔드포인트), `spring-boot-starter-opentelemetry`(otel 프로파일) | |

### 4.2 패키지 구조

```
io.datadynamics.ranger.demo
  DemoApplication
  config/        # 기록용 인터셉터 등록(Customizer 빈), TraceInterceptor 등록(WebConfig)
  trace/         # SdkCalls, SdkCall, HttpAttempt, TraceInterceptor (§4.3)
  seed/          # DemoSeeder (ApplicationRunner, 재시도 포함)
  web/
    dashboard/ servicedefs/ services/ policies/ roles/ principals/
    zones/ tags/ pdp/ kms/ pac/ lab/ calls/
  web/support/   # 예외 → 화면 변환(Failure, ErrorAdvice), 폼 → 모델 변환(Inputs), JSON(Json)
```

컨트롤러는 화면 요청을 받아 **SDK 메서드를 직접 호출**한다. 별도 서비스 계층으로 감싸지 않는다 — 데모를 읽는 사람이 "이 버튼 = 이 SDK 호출"을 한눈에 보게 하기 위해서다. 공통 로직(기록, 예외 처리)만 `trace/`와 `web/support/`에 둔다.

### 4.3 화면 구성과 호출 기록 (모든 화면 공통)

모든 화면은 `templates/fragments.html`의 조각으로 같은 틀을 쓴다: `head(title)`, `nav(active)`(상단 메뉴와 "? 도움말"),
본문(`main`), 오른쪽 도움말(`templates/help/<화면>.html`의 `help` 조각), 본문 맨 아래 요청 표(`trace`).

```
┌ 결과 패널 (fragments/call :: panel) ─────────────────────┐
│ [성공] 23 ms · HTTP 요청 2건                              │
│ admin.policies().apply(policy)          ← 실행한 SDK 코드 │
│ ▸ 결과: RangerPolicy (JSON)  |  또는 예외 상자            │
└───────────────────────────────────────────────────────────┘
┌ 이 화면에서 클라이언트가 보낸 요청 (fragments :: trace) ─┐
│ # 클라이언트 메서드 경로(펼치면 URI·본문) 시도 상태 시간  │
└───────────────────────────────────────────────────────────┘
```

- **HTTP 시도**: 각 클라이언트 빌더에 `RequestInterceptor`를 Customizer 빈으로 등록한다(`config/RecordingConfig`). SDK는 시도마다 요청 스레드에서 `begin` → `onRequestBody`(본문이 있을 때) → `onResponse(status, body)` 또는 `onError`를 부른다. `trace/SdkCalls`가 이것을 세 곳에 쌓는다: 실행 중인 SDK 호출(`SdkCall.attempts`), 현재 웹 요청(`TraceInterceptor`가 시작·정리하는 ThreadLocal 목록, 뷰의 `trace` 속성), 앱 전체 이력(최근 300건). 본문은 20,000자까지 보관하고, 요청 본문의 비밀번호는 SDK의 `Masking`으로 가린다.
- **SDK 호출 문자열**: 컨트롤러가 `calls.run("admin.policies().apply(policy)", () -> admin.policies().apply(p))` 형태로 실행한다. 문자열은 손으로 쓴다(리플렉션·AOP로 생성하지 않음 — 읽는 코드가 실제 예제와 같아야 함).
- **htmx 동작**: 폼 동작은 결과 조각(`fragments/call :: call`)만 돌려준다. 이 조각에는 그 동작에서 보낸 요청 표(`actionTrace`)가 함께 붙는다. 끼워 넣은 뒤 `demo.js`가 그 안의 코드 블록을 Monaco로 바꾼다.
- **오류**: `SdkCalls.run` 안의 예외는 결과 패널의 예외 상자(`fragments :: failure`)로, 그 밖의 SDK 예외·잘못된 입력은 `ErrorAdvice`가 `failure.html`로 보여준다.
- **요청 기록 화면**: 앱 전체 이력(헬스 체크·시더 포함), 클라이언트별 필터, 최근 SDK 호출, Micrometer 요약.

### 4.4 설정 (`application.yml`)

```yaml
ranger:
  admin:
    urls: ${RANGER_ADMIN_URLS:http://ranger-admin:6080}
    auth: {type: basic, username: admin, password: ${RANGER_ADMIN_PASSWORD}}
  pdp:
    urls: ${RANGER_PDP_URLS:http://ranger-pdp:6500}
    service: demo_hive
    service-type: hive
    auth: {type: header, header: X-Forwarded-User, user: demo-app}
    cache: {ttl: 0s}                  # 기본 off, 화면에서 캐시 켠 클라이언트와 비교
  kms:
    urls: ${RANGER_KMS_URLS:http://ranger-kms:9292}
    auth: {type: hadoop-simple, user: keyadmin}
demo:
  seed: {enabled: true}
```

호스트에서 `mvn spring-boot:run`으로 앱만 띄울 때는 `localhost` URL을 쓰는 `local` 프로파일을 제공한다(앱 코드만 고치며 개발하는 경우).

## 5. 시드 데이터

`DemoSeeder`(ApplicationRunner)가 기동 시 Admin 준비를 기다린 뒤(지수 backoff, 최대 5분) 아래를 넣는다. 모두 이름 기준 upsert라 재실행해도 같다. 실패하면 앱은 계속 뜨고 대시보드에 시드 상태와 원인 예외를 보여준다. 대시보드에 "시드 다시 실행" 버튼.

| 대상 | 내용 | 사용하는 SDK |
|---|---|---|
| 사용자 | `alice`, `bob`, `mallory`, `keyadmin`(KMS 권한 확인용), `demo-app` | `users().create` (없을 때만) |
| 그룹 | `analyst`(alice), `engineer`(bob) | `groups().create`, `addUser` |
| 서비스 | `demo_hive`(hive), `demo_hdfs`(hdfs), `dev_kms`(kms) — 접속 대상 없는 설정값 | `services().apply` |
| 역할 | `analysts` | `roles().apply` |
| 정책 | `seed/demo_hive.yaml`을 Policy-as-Code로 plan → apply (allow·deny·mask·row filter 각 1개 이상) | `PolicyAsCode` |
| KMS 정책 | `dev_kms`에서 `keyadmin`에 모든 키 작업 허용 | `policies().apply` |
| 존 | `finance` 존(demo_hive `database=finance`) | `zones().create` (ZONES_V2 감지 시) |
| 태그 | 태그 정의 `PII`, `demo_hive` 리소스 `sales.customers.email`에 매핑 | `tags()` |

## 6. 화면 구성 (SDK 기능 매핑)

| 화면 | 기능 | SDK |
|---|---|---|
| **Dashboard** | Admin/PDP/KMS 연결 상태(노드별 UP/COOLDOWN, 인증 방식), 서버 버전, 기능 감지 표(`Feature` 전체 × supports), 시드 상태 | `/actuator/health` 내용, `capabilities().supports/serverVersion`, `pdp.ready()`, `kms.status()` |
| **Service Defs** | 목록, 상세(리소스 계층·access type·mask type 표 + 원본 JSON) | `serviceDefs().stream/getByName` |
| **Services** | 목록(타입 필터), 생성/수정/삭제, upsert, 연결 테스트 | `services().search/apply/delete/testConnection` |
| **Policies** | 필터 검색(service·zone·user·group·role·label·resource·enabled, 페이지), 상세, 폼 편집(리소스/allow/deny/mask/row-filter), JSON 편집, `create`/`update(checkVersion)`/`apply`/`serverApply`, 항목 추가·주체 제거, 리소스별·사용자별 정책 조회, export/import JSON | `policies().*`, `PolicyFilter` |
| **Roles** | 목록, 생성/수정(멤버·관리자), upsert, grant/revoke, 사용자별 역할 | `roles().*` |
| **Users & Groups** | 검색, 생성/삭제, 그룹 멤버십 추가·제거, 사용자의 그룹 | `users()`, `groups()` |
| **Security Zones** | 목록/헤더, 생성/수정, 부분 수정(v2 change request), 존 리소스 조회 | `zones().*` |
| **Tags** | 태그 정의, 태그, 서비스 리소스, 매핑/해제 | `tags().*` |
| **PDP Playground** | 단일 판정(user·groups·resource·access → ALLOWED/DENIED + 정책 id, mask/row filter 결과), 다건 판정, 리소스 권한 조회, 캐시 켠/끈 클라이언트 비교(지연시간), "정책 바꾸고 다시 판정" 바로가기 | `pdp.authorize/authorizeMulti/permissions/isAllowed/clearCache` |
| **KMS** | 키 목록·메타데이터, 생성(cipher·length·attributes), rollover, 버전 목록, EEK 생성 → 복호화 → 재암호화 흐름, 삭제 | `RangerKmsClient.*` |
| **Policy as Code** | YAML 편집기(시드 YAML 로드) → Plan(생성/수정/삭제 diff) → Apply, 서비스 정책 Export → YAML, prune/managed_label 설명 | `PolicyAsCode.plan/apply/export`, `SpecLoader` |
| **Resilience Lab** | ① 장애 조치: 앞 URL이 죽은 노드(`http://ranger-admin-down:6080`)인 별도 Admin 클라이언트로 호출 → 시도 #1 연결 실패, #2 성공, cooldown 상태 표시 ② 재시도: 짧은 read timeout 클라이언트 ③ 예외 카탈로그: 404·409·400·401(틀린 비밀번호 클라이언트)·403(PDP 비위임 사용자)·`UnsupportedFeatureException`(GDS)을 버튼으로 일으켜 매핑된 예외 타입 확인 ④ 낙관적 잠금: 두 번 읽은 정책을 차례로 `update(checkVersion=true)` → 충돌 | 별도 빌더로 만든 클라이언트들, `RangerException` 계층 |
| **Calls** | 최근 SDK 호출/HTTP 시도 로그, `/actuator/metrics/ranger.client.requests` 요약, (otel 프로파일) Jaeger 링크 | `RequestInterceptor`, Micrometer |

공통 UX: 왼쪽 내비게이션, 각 화면 우측(또는 하단)에 §4.3 결과 패널. 파괴적 작업(삭제·prune apply)은 확인 대화상자. 시드 리소스를 지우면 "시드 다시 실행"으로 복구.

## 7. 빌드

`ranger-client-demo/app/Dockerfile` (빌드 컨텍스트는 저장소 루트, `.dockerignore`로 `python/`, `target/` 제외):

```dockerfile
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY java/ java/
COPY spec/ spec/
RUN --mount=type=cache,target=/root/.m2 \
    mvn -q -f java/pom.xml install -DskipTests \
        -pl ranger-sdk-spring-boot-starter,ranger-sdk-policy-as-code,ranger-sdk-opentelemetry -am
COPY ranger-client-demo/app/ ranger-client-demo/app/
RUN --mount=type=cache,target=/root/.m2 mvn -q -f ranger-client-demo/app/pom.xml package -DskipTests

FROM eclipse-temurin:21-jre
COPY --from=build /src/ranger-client-demo/app/target/ranger-client-demo.jar /app.jar
ENTRYPOINT ["java", "-jar", "/app.jar"]
```

- 현재 작업 트리의 SDK를 그대로 빌드하므로 SDK를 고치고 `docker compose up --build demo-app`이면 데모에 반영된다.
- SDK 테스트는 이미 CI에서 돌므로 이미지 빌드에서는 건너뛴다.

## 8. 테스트

| 종류 | 내용 |
|---|---|
| 앱 단위 | `@WebMvcTest` + 모의 클라이언트로 각 컨트롤러가 올바른 SDK 메서드를 부르고 예외를 화면 모델로 바꾸는지 |
| 기록기 | `CallRecorder`가 재시도/장애 조치 시도를 순서대로 남기는지 (core 테스트의 `MockServer` 재사용 불가 → 앱 테스트용 경량 스텁) |
| 스모크 | `ranger-client-demo/scripts/smoke.sh`: `docker compose up --wait` 후 `/actuator/health`가 UP이고 Dashboard·PDP 판정·KMS 키 생성이 200인지 |
| E2E(선택) | Testcontainers `ComposeContainer`로 `@Tag("e2e")` — 기본 빌드에서는 제외 |

## 9. 확장 여지

- `docker-compose.kerberos.yml`: KDC 컨테이너 + Admin/KMS/PDP Kerberos 설정, 앱은 `ranger-sdk-auth-kerberos`로 keytab 인증. (README가 밝히는 "실제 KDC 대상 미검증" 항목을 이 데모로 메울 수 있음)
- TLS 프로파일: 자체 서명 CA로 `Tls.pem` 확인.
- Admin 2대 HA 구성으로 실제 노드 장애 조치 확인(현재 Resilience Lab은 죽은 URL로 흉내).
- `rangerctl` 컨테이너: 같은 스택에 Python CLI로 plan/apply.

## 10. 구현 순서

1. **스택**: compose + KMS/PDP Dockerfile, 수동으로 Admin 6080·KMS·PDP `/health/ready` 확인. (위험 요소가 가장 큰 단계: KMS 설치 스크립트, PDP 설정값)
2. **앱 골격**: Boot 4 + starter, Dashboard(헬스·기능 감지), CallRecorder, Dockerfile, 시더.
3. **Admin 화면**: Policies → Services/ServiceDefs → Roles/Users/Groups → Zones → Tags.
4. **PDP Playground, KMS, Policy as Code**.
5. **Resilience Lab, Calls, otel 프로파일**.
6. README, smoke.sh, 루트 README에 데모 링크.

## 11. 결정이 필요한 항목

| 항목 | 제안 | 대안 |
|---|---|---|
| UI 기술 | Thymeleaf + htmx (Node 불필요) | React/Vite SPA — 화면이 풍부해지지만 빌드 체인 추가 |
| Kerberos | 1차 제외, 확장 profile로 | 1차부터 KDC 포함 (기동 복잡도·시간 증가) |
| 앱 위치 | `ranger-client-demo/app` 독립 Maven 프로젝트 | `java/` reactor 모듈 (SDK 빌드에 Boot 앱이 섞임) |
| KMS/PDP 바이너리 | 빌드 시 archive.apache.org에서 받아 sha512 검증 | 사전 빌드 이미지를 사내/공개 레지스트리에 올려 두기 (빌드 시간 단축, 관리 부담) |
