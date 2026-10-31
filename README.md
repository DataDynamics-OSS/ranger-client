# Apache Ranger Java & Python Client

하나의 API 스펙(`spec/`)에서 출발한 Apache Ranger SDK. Java와 Python이 같은 모델, 같은 예외 이름, 같은 동작(재시도·장애 조치·기능 감지)을 가지며, 두 언어가 **같은 계약 테스트**를 통과한다.

- 대상 버전: Ranger **2.9.0** (서버 2.5 – 2.9 지원, 기능은 서버별로 감지)
- 범위: Admin REST (servicedef, service, policy, role, security zone v2, user/group, tag), KMS, PDP(`/authz/v1`), GDS(Ranger 3.0+ 전용)
- Java 11+ (의존성: JDK HttpClient, Jackson) · Python 3.9+ (의존성: httpx, pydantic v2)

## 저장소 구성

```
spec/
  models.yaml            # 와이어 모델 정의 (Java 모델 생성 원본, Python 모델 검증 기준)
  features.yaml          # 기능 × 최소 서버 버전 × 탐지 경로
  contract/scenarios.yaml  # HTTP 계약 시나리오 (두 언어 공통)
  contract/pac.yaml        # policy-as-code plan 계약 (두 언어 공통)
  contract/tracing.yaml    # OpenTelemetry span 계약 (두 언어 공통)
tools/gen_java_models.py # spec/models.yaml → Java 모델 생성 (--check: CI 용)
java/                    # Maven 멀티 모듈 (groupId io.datadynamics.ranger.client)
python/                  # PyPI 패키지 ranger-sdk (import ranger_sdk), rangerctl CLI 포함
```

| Java 모듈 | 내용 |
|---|---|
| `ranger-sdk-model` | 불변 모델 + Builder (생성 코드), 미지 필드 보존, open enum |
| `ranger-sdk-core` | Transport(장애 조치·재시도·세션 쿠키), 인증, TLS, 예외, 기능 감지 |
| `ranger-sdk-admin` / `-pdp` / `-kms` | 서버별 클라이언트 |
| `ranger-sdk-auth-kerberos` | SPNEGO (keytab / ticket cache, 자동 relogin) |
| `ranger-sdk-policy-as-code` | YAML → Plan → Apply, Export |
| `ranger-sdk-opentelemetry` | OpenTelemetry 트레이싱: HTTP 시도마다 CLIENT span + W3C trace context 전파 (`opentelemetry-api`만 의존) |
| `ranger-sdk-authz-adapter` | `ranger-authz-api` 2.9의 `RangerAuthorizer` 구현 (원격 PDP 호출) |
| `ranger-sdk-bundle` | 전체를 하나로 묶은 shaded jar (Jackson/SnakeYAML relocate) |
| `ranger-sdk-spring-boot-starter` | Spring Boot 4 자동 구성: `ranger.*` 프로퍼티 → 클라이언트 빈, Actuator 헬스, Micrometer 메트릭 (**Java 17+**) |
| `ranger-sdk-contract-tests` | `spec/contract` 실행기 |

## 빠른 시작

### Java

```java
RangerAdminClient admin = RangerAdminClient.builder()
        .urls("https://ranger1:6182", "https://ranger2:6182")
        .auth(KerberosAuth.keytab("svc-app@CORP.LOCAL", Path.of("/etc/security/app.keytab")))
        .tls(Tls.pem(Path.of("/etc/pki/ranger-ca.pem")))
        .build();

RangerPolicy policy = RangerPolicy.builder()
        .service("cm_hive").name("sales_read")
        .resource("database", "sales").resource("table", "*")
        .allow(PolicyItem.groups("analyst").accesses("select"))
        .build();
admin.policies().apply(policy);          // 이름 기준 upsert (재실행 안전)

RangerPdpClient pdp = RangerPdpClient.builder()
        .urls("https://pdp:6500")
        .auth(JwtAuth.supplier(() -> readToken()))
        .service("dev_hive", "hive")
        .build();
boolean ok = pdp.isAllowed("alice", ResourceInfo.of("database", "sales", "table", "orders"), "select");
```

### Python

```python
from ranger_sdk import RangerAdmin, AsyncRangerAdmin
from ranger_sdk.auth import Basic, Kerberos

with RangerAdmin(["https://ranger1:6182", "https://ranger2:6182"],
                 auth=Kerberos(principal="svc-app@CORP.LOCAL", keytab="/etc/security/app.keytab"),
                 verify="/etc/pki/ranger-ca.pem") as admin:
    for p in admin.policies.iter("cm_hive"):
        print(p.id, p.name)

async with AsyncRangerAdmin("https://ranger:6182", auth=Basic("admin", "secret")) as admin:
    role = await admin.roles.find("analysts")
```

동기/비동기 클라이언트는 같은 코드에서 나온다(각 연산은 `Request`를 yield 하는 제너레이터 하나로 작성되고 Sync/Async runner가 실행).

### Spring Boot (starter)

Spring Boot 4.x, Java 17+ 전용 모듈이다(나머지 SDK는 Java 11 유지).

```xml
<dependency>
    <groupId>io.datadynamics.ranger.client</groupId>
    <artifactId>ranger-sdk-spring-boot-starter</artifactId>
    <version>0.2.0</version>
</dependency>
<!-- Kerberos를 쓸 때만 -->
<dependency>
    <groupId>io.datadynamics.ranger.client</groupId>
    <artifactId>ranger-sdk-auth-kerberos</artifactId>
    <version>0.2.0</version>
</dependency>
```

```yaml
ranger:
  admin:
    urls: [https://ranger1:6182, https://ranger2:6182]
    server-version: 2.9.0              # 주면 기능 탐지 요청 생략
    auth: {type: kerberos, principal: svc-app@CORP.LOCAL, keytab: /etc/security/app.keytab, krb5-conf: /etc/krb5.conf}
    tls: {ca: /etc/pki/ranger-ca.pem}  # 또는 truststore/truststore-password, keystore(mTLS), insecure
    retry: {max-attempts: 3, base-delay: 200ms}
    read-timeout: 30s
  pdp:
    urls: [https://ranger-pdp:6500]
    service: dev_hive
    service-type: hive
    auth: {type: jwt, token-file: /run/secrets/pdp.jwt}   # 만료·401 시 파일을 다시 읽음
    cache: {ttl: 30s, max-entries: 10000}                 # 기본 off
  kms:
    urls: [https://ranger-kms:9393]
    auth: {type: hadoop-simple, user: hdfs}
```

- **클라이언트 빈**: `urls`가 있는 서버만 `RangerAdminClient` / `RangerPdpClient` / `RangerKmsClient` 싱글턴 빈으로 등록하고, 컨텍스트 종료 시 닫는다. 애플리케이션이 같은 타입의 빈을 정의하면 자동 구성은 물러난다. 빌더 조정은 `RangerAdminClientCustomizer` 등 커스터마이저 빈으로 한다.
- **인증**: `type`은 `none | basic | jwt | kerberos | header | hadoop-simple`. 비밀값은 `password-file` / `token-file` 또는 `${ENV}` placeholder를 쓰며, 설정 파일에 평문으로 쓰면 경고 로그를 남긴다(값은 출력하지 않음). 필수 값이 빠지면 프로퍼티 이름을 담은 오류로 기동이 실패한다.
- **헬스** (`/actuator/health`): `rangerAdmin`(servicedef 1건 조회, 인증 포함), `rangerPdp`(`/health/ready`, 재시도 없음), `rangerKms`(`/kms/api/status`). 상세에 노드별 `UP`/`COOLDOWN`, 인증 방식을 보여주고, 401/403은 `reachable=true` + `hint`로 장애와 구분한다. 끄기: `management.health.ranger.enabled=false`.
- **트레이싱** (`ranger-sdk-opentelemetry` + `OpenTelemetry` 빈이 있을 때, 예: `spring-boot-starter-opentelemetry`): 클라이언트마다 `RangerTracing`을 연결한다. Micrometer Tracing의 OTel 브리지가 현재 span을 OTel context에 두므로, 들어온 요청의 trace 아래에 Ranger 호출 span이 붙는다. 끄기: `ranger.tracing.enabled=false`.
- **메트릭** (`MeterRegistry` 빈이 있을 때): `ranger.client.requests` 타이머(태그 `server`, `node`, `method`, `status`, `outcome`), `ranger.client.retries` 카운터. 경로는 태그에 넣지 않는다(카디널리티). 끄기: `ranger.metrics.enabled=false`.
- IDE 자동완성용 `spring-configuration-metadata.json`을 포함한다.

### OpenTelemetry 트레이싱

```java
RangerAdminClient admin = RangerAdminClient.builder()
        .urls("https://ranger:6182")
        .interceptor(RangerTracing.create(openTelemetry))   // ranger-sdk-opentelemetry
        .build();
```

```python
# pip install 'ranger-sdk[otel]'
from ranger_sdk.otel import OpenTelemetryInterceptor
admin = RangerAdmin("https://ranger:6182", interceptors=[OpenTelemetryInterceptor()])
```

- HTTP **시도마다** CLIENT span 하나 (재시도·페일오버도 각각 span, OTel HTTP semconv 방식). 호출 시점의 현재 span이 부모가 된다.
- span 이름은 HTTP 메서드, 속성은 `http.request.method`, `url.full`, `server.address`, `server.port`, `http.response.status_code`, `http.request.resend_count`, `error.type` + `ranger.server`(admin/pdp/kms), `ranger.request_id`(= `X-Request-Id`). 4xx/5xx와 연결 실패는 ERROR.
- 각 시도에 그 span의 `traceparent`를 실어 보낸다(설정된 propagator 사용).
- OTel Java agent의 JDK HttpClient 계측이나 `opentelemetry-instrumentation-httpx`와 함께 쓰면 같은 요청이 두 번 기록되므로 하나만 쓴다.
- 기반은 core의 `RequestInterceptor`(Java) / `interceptors`(Python) 훅으로, 시도마다 헤더를 추가하고 결과를 받는다. 다른 트레이서도 이 훅으로 붙일 수 있다.
- 시도 객체는 요청·응답 본문도 받을 수 있다: Java는 `RequestInterceptor.Attempt`의 기본 메서드 `onRequestBody`, `onResponse(int, byte[])`, Python은 선택 메서드 `on_request_body(content_type, body)`, `on_response_body(status, body)`(`on_response` 직전에 호출). 본문에는 KMS 키 재료 같은 비밀이 들어 있을 수 있으니 그대로 로그에 남기지 말 것. 데모 앱의 요청 표가 이 훅을 쓴다.

## 공통 동작

| 항목 | 동작 |
|---|---|
| 장애 조치 | URL round-robin, 실패한 노드는 30초 cooldown |
| 재시도 | 연결 실패·429·5xx에 대해 **멱등 요청만** (GET/PUT/DELETE, PDP POST). 지수 backoff + jitter, `Retry-After` 존중 |
| 401 | 인증 갱신(JWT 재발급, 세션 쿠키 폐기) 후 1회 재전송 — 재시도 횟수에 포함하지 않음 |
| 세션 | `RANGERADMINSESSIONID` 쿠키 재사용으로 매 요청 SPNEGO 협상 생략 |
| 추적 | `X-Request-Id`를 재시도 간에 동일하게 유지, 예외에 포함 |
| 마스킹 | 로그·예외·`toString()`에서 비밀번호/토큰/키 material 마스킹 |
| 호환성 | 서버가 보낸 미지 필드를 보존하여 update 시 그대로 돌려보냄, 미지 enum 값은 `UNKNOWN` + 원문 보존 |

예외 계층 (두 언어 동일 이름): `RangerException` ← `RangerTransportException`, `RangerAuthenticationException`(401), `RangerAccessDeniedException`(403), `RangerNotFoundException`(404), `RangerConflictException`(409 또는 "already exists" 400), `RangerValidationException`(400), `RangerServerException`(5xx), `UnsupportedFeatureException`.

기능 감지: `admin.capabilities().supports(Feature.ZONES_V2)` / `admin.capabilities.supports(Feature.ZONES_V2)`. `serverVersion("2.9.0")`을 주면 탐지 요청 없이 `features.yaml` 기준으로 판단한다.

## Policy as Code

```yaml
# policies/hive.yaml
service: cm_hive
prune: true               # spec에 없는 서버 정책 삭제
managed_label: gitops     # prune 대상을 이 label이 붙은 정책으로 한정
roles:
  - {name: analysts, users: [alice]}
policies:
  - name: sales_read
    labels: [gitops]
    resources: {database: sales, table: "*", column: "*"}
    allow: [{groups: [analyst], roles: [analysts], accesses: [select]}]
    deny:  [{users: [mallory], accesses: [select]}]
```

- 비교 전 정규화: 서버 관리 필드(id, version, 시간 등) 제거, 기본값 채움, 순서가 의미 없는 목록(값·사용자·access) 정렬. data mask / row filter 항목은 순서가 의미 있으므로 유지.
- Apply는 plan 시점의 version으로 갱신 → 그 사이 UI에서 바뀐 정책은 `RangerConflictException`.
- Python `PolicyAsCode(admin).plan(load_specs("policies/"))`, Java `new PolicyAsCode(admin).plan(SpecLoader.load(List.of(Path.of("policies"))))`.

## rangerctl

```bash
pip install 'ranger-sdk[cli,kerberos]'
rangerctl plan -f policies/ --detailed-exitcode   # 변경 있으면 exit 2 (CI)
rangerctl apply -f policies/ --auto-approve
rangerctl policy export -s cm_hive > policies/hive.yaml
rangerctl policy list -s cm_hive -o table
rangerctl authz check alice dev_hive database=sales,table=orders select   # 허용 0 / 거부 3
rangerctl capabilities
rangerctl kms keys
```

프로필: `~/.config/rangerctl/config.yaml` (또는 `$RANGERCTL_CONFIG`). 비밀값은 `password_env`, `password_file`, `token_env`, `token_file`, `token_command`로만 지정한다. 예시는 `python/src/ranger_sdk/cli/config.py` 참고.

## 데모

`ranger-client-demo/`에 Ranger 2.9.0 스택(Admin · KMS · PDP)과 SDK 기능을 직접 실행해 보는 Spring Boot 웹 UI가 있다. Docker만 있으면 된다.

```bash
cd ranger-client-demo && docker compose up -d --build --wait   # http://localhost:8080
```

자세한 내용은 [ranger-client-demo/README.md](ranger-client-demo/README.md).

## 빌드와 테스트

```bash
# Java (JDK 11+; Maven 3.8+)
cd java && mvn install

# Python
cd python && python -m venv .venv && .venv/bin/pip install -e '.[dev,cli]' && .venv/bin/pytest

# 모델 스펙을 바꾼 경우
python3 tools/gen_java_models.py         # Java 모델 재생성
```

두 언어의 테스트가 `spec/contract/*.yaml`, `spec/features.yaml`, `spec/models.yaml`을 직접 읽으므로, 스펙을 바꾸면 양쪽 테스트가 함께 깨진다. Python 테스트는 Java 모델이 스펙과 일치하는지(`gen_java_models.py --check`)도 확인한다.

## 설계 문서와 다른 점 / 남은 작업

- **GDS**: Ranger 2.x 릴리스에는 GDS REST가 없다 (2.9.0 소스에 `GdsREST` 없음). `features.yaml`에서 `min_version: 3.0.0`으로 두고, 클라이언트는 맵 기반으로 제공하며 2.x 서버에서는 `UnsupportedFeatureException`.
- **Python async PDP**는 `async with RangerPdp(...)`가 아닌 별도 클래스 `AsyncRangerPdp` (Admin/KMS와 동일한 패턴).
- **Java `*Async` 메서드**는 executor 위에서 동기 호출을 실행한다 (진정한 non-blocking 아님).
- **로깅**: 추가 의존성을 피하려고 SLF4J 대신 `System.Logger` 사용 (SLF4J/Log4j2 브리지로 연결 가능).
- **Java TLS 호스트명 검증 해제**는 JDK HttpClient 제약상 JVM 전역 설정(`Tls.disableHostnameVerificationGlobally()`).
- **Ranger 2.x 특이 동작 반영**: 존재하지 않는 이름 조회에 404 대신 400을 반환하는 경우를 SDK가 `RangerNotFoundException`으로 변환한다(`find()`는 빈 값).
  - role: 400 "Role with name: x does not exist". 2.9는 400 "User doesn't have permissions to get details for x"를 반환하므로, 이때는 `/roles/names`에 이름이 없을 때만 not found로 보고 있으면 원래 예외(권한 문제)를 그대로 던진다.
  - user/group (`/xusers/.../userName|groupName/x`): 400 + `messageList[].name = DATA_NOT_FOUND` → 모든 요청에 공통 적용.
  - security zone (`/zones-v2/name/x`): 400 "security-zone with name: x does not exist".
  - role 이름으로 삭제: 400 "error code[4007], reason[No RangerRole found for name[x]]".
  - tag / tagdef / service resource id 조회·삭제: 메시지 없는 400 `{"statusCode":400}`. 실제 실패(예: 리소스에 매핑된 태그 삭제)는 메시지가 있으므로 그대로 `RangerValidationException`.
  - KMS: 없는 키의 `_metadata`·`_currentversion`·`keyversion`은 `200 {}`, `_versions`는 `200 []`. 삭제·rollover는 500 `RemoteException` "Key x does not exist" / "Can't find Metadata" → 서버 장애가 아니므로 재시도하지 않고 노드도 cooldown에 넣지 않는다.
- **exportJson**: Ranger가 `Content-Type: text/json`으로 응답하고 `Accept: application/json`만 보내면 406이므로 `Accept: application/json, text/json`을 보낸다.
- **PDP request id**: 요청 본문의 `requestId`를 `X-Request-Id` 헤더로도 보내 헤더·본문·결과·서버 로그가 같은 id를 갖는다.
- **Policy as Code apply 순서**: role 변경을 먼저 적용한다(없는 role을 참조하는 정책은 Ranger가 거부). spec의 mask/row filter 항목 안쪽 키(`data_mask_info: {data_mask_type: ...}`)도 snake_case를 받는다.
- **Spring Boot starter**는 Boot 4.x 전용(Actuator 헬스 API가 4.0에서 `spring-boot-health` 모듈로 이동). Boot 3.x 지원이 필요하면 별도 모듈이 필요하다.
- 미구현: Audit API (P3), OpenAPI 생성. Kerberos는 실제 KDC 대상으로 검증하지 않았다.
