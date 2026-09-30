# 공고 분석(지원 형태·금액·조건)

## 목적

모집 중·예정 공고마다 지원 형태, 지원 금액, 선정 규모, 신청 조건(필수·제외·우대), 문의처와 v2부터 제출 서류·선정 절차·
평가 기준·일정을 공고 원문(공식 첨부 포함) 근거와 함께 미리 추출해 상세 화면에서 바로 보여 줍니다. 분석은 백그라운드에서만 실행하며 상세 GET, 목록·검색 카드 요약,
내 기업 조건 확인은 모두 저장된 결과만 읽습니다.

## 흐름

```text
SupportProgramAnalysisWorker (@Scheduled, 전용 단일 스레드)
  → SupportProgramAnalysisService.runNext()
  → SupportProgramAnalysisRepository.countAttemptedSince  (일일 한도 확인)
  → SupportProgramAnalysisRepository.claimNext            (실행권 선점, 짧은 DB transaction)
  → SupportProgramRepository                             (현재 공고 조회)
  → SupportProgramEvidenceService.sourceDocument         (기업마당만: 원문 캐시 재사용 또는 BizInfo Facade로 재수집)
  → SupportProgramAttachmentTextFacade.load              (네 제공처 공식 첨부 수집·본문 추출)
  → AiSupportProgramAnalysisClient                       (DB transaction 밖)
  → AI Service POST /internal/v1/support-program-analyses/analyze → OpenAI
  → SupportProgramAnalysisRepository.complete / fail     (같은 lease_token일 때만 반영)
```

- 요청 필드는 AI Service 한도에 맞춰 코드 포인트 단위로 자릅니다(제목 500, 기관 255, 요약 20,000, 대상 8,000,
  신청 기간 1,000, 신청 방법 8,000, 상세 원문 30,000). 기업마당 외 제공처는 `detailText = null`입니다.
- 응답은 Client 경계(`AiSupportProgramAnalysisMapper`)에서 버전·열거값·길이·지역(17개 시·도 약칭과 `전국`)·날짜·배점·근거를
  검증합니다. 요청에 보내지 않은 필드(`APPLICATION_METHOD`·`DETAIL_TEXT`·첨부가 없을 때 `ATTACHMENT`)를 근거로 든 응답과,
  `ATTACHMENT` 근거인데 보낸 첨부 이름이 아니거나 다른 근거에 `attachmentName`이 있는 응답은 거부합니다.
  글자 수는 1,000자(인용 1,000자), 목록 개수는 AI Service 계약 최대값(조건 30, 제출 서류 30, 선정 절차 10, 평가 기준 20,
  일정 15)까지 받습니다. `points`는 0~1,000 실수, `date`는 실제 날짜인 `YYYY-MM-DD`여야 합니다.
- AI Service 실행 한도는 100초(모델 90초)이므로 전용 RestClient(`app.ai-service.support-program-analysis-read-timeout`,
  기본 120초)를 씁니다. 실행권(`lease-seconds`, 기본 300초)은 원문·첨부 수집 시간을 더해도 이보다 길어야 합니다.

## 첨부 입력(v2)

`SupportProgramAttachmentTextFacade`가 신청 양식 발견·중복 지원 검토가 쓰는 기존 제공처별 첨부 Client
(`BizInfoAttachmentClient`·`MsitAttachmentClient`·`KStartupAttachmentClient`·`CnTradeNoticeAttachmentClient`)로 공식 상세가
직접 연결한 PDF/HWP/HWPX/DOCX/XLSX를 내려받고, 같은 `SupportProgramDocumentParser`로 본문을 추출합니다. 다운로드·파일
크기·호스트 검증 규칙은 기존 Client 그대로입니다.

- 보낼 첨부 고르기(`AiSupportProgramAnalysisMapper`): 이름에 `공고`가 있는 첨부를 먼저, 나머지는 원래 순서로 최대 8개를
  보냅니다. 본문 합계가 40,000 코드 포인트를 넘지 않도록 앞 첨부부터 남은 한도만큼 자르고, 한도를 다 쓰면 이후 첨부는
  보내지 않습니다. 이름은 255자로 자르고 비어 있으면 `첨부 N`을 씁니다. 첨부가 없으면 `attachments: []`입니다.
- 파일별 파싱 실패(미지원 형식·암호화·크기 초과·본문 50자 미만·손상)는 그 파일만 제외하고 셉니다.
- 첨부 목록 자체가 없거나 쓸 수 없는 경우(`UNSUPPORTED` 첨부 없음, `TOO_LARGE` 목록 한도 초과, `NOT_FOUND`, `INVALID`
  페이지 검증 실패)는 첨부 없이 분석합니다. 제공처 연결 실패·시간 초과(`UNAVAILABLE`)는 `SOURCE_UNAVAILABLE`로 실패하고
  재시도합니다. 기존 Client는 개별 파일 다운로드 실패도 목록 전체 실패로 돌려주므로(크기 초과 파일만 건너뜀), 한 파일의
  404는 첨부 없이 분석하고 한 파일의 연결 실패·시간 초과는 `SOURCE_UNAVAILABLE`이 됩니다.
- 실제로 보낸 첨부 이름은 저장 내용의 `sourceAttachmentNames`로 남기고 상세 응답에 그대로 싣습니다.
- 로그는 보낸 수·파싱 제외 수·한도로 뺀 수와 목록 실패 사유 코드만 남깁니다.

## 저장 구조(V46 `support_program_analysis`)

`(source_code, source_program_id)` 기본 키와 `support_program`에 대한 FK(연쇄 삭제 없음)를 가집니다. 공고 행은
동기화에서 UPSERT·비노출 처리만 되고 삭제되지 않으므로 FK로 참조 무결성을 보장합니다. FK를 위해 식별자 컬럼은
`support_program`과 같은 `utf8mb4_0900_ai_ci`를 씁니다.

| 저장 상태 | 의미 | 상세 응답 |
|---|---|---|
| `PENDING` | 실행권을 얻었지만 결과가 아직 없음(신규 공고 또는 내용 변경 직후) | `NOT_ANALYZED` |
| `COMPLETED` | `analysis_json`·버전·모델·`analyzed_at` 필수(CHECK) | 지문 일치 시 `COMPLETED` |
| `FAILED` | `failure_code` 필수(CHECK), `analysis_json`은 없음 | 지문 일치 시 `FAILED` |

행이 없거나 지문이 다르면 `NOT_ANALYZED`입니다. 실행권 `lease_token`·`lease_until`은 함께 있거나 함께 비어야 합니다(CHECK).

## 공고 지문

지문은 `SupportProgramAnalysisMapper.xml`의 `programFingerprint` SQL 조각 한 곳에서만 정의합니다.

```text
SHA2(CONCAT_WS(CHAR(31), title, organization, summary, target_description,
               application_period_raw, COALESCE(application_method, ''), source_url), 256)
```

후보 선택·실행권 선점·상세 조회·목록 요약 조회가 같은 조각을 씁니다. 저장된 `program_fingerprint`가 현재 값과 다르면 이전 결과는
보이지 않고 다시 분석 대상이 됩니다. `model`은 기록용입니다.
`support_program.content_hash`는 Core에서 채우지 않으므로 쓰지 않습니다. 기업마당 상세 원문과 첨부는 지문에 포함하지
않으므로 목록 API 필드가 같고 상세 페이지·첨부만 바뀐 경우는 재분석하지 않습니다.

## 분석 버전

Core는 `AiSupportProgramAnalysisMapper.EXPECTED_ANALYSIS_VERSION`(`govbiz-support-program-analysis-v2`)만 저장합니다.

- 응답 버전이 다르면 `AI_VERSION_MISMATCH`로 실패하고 재시도하지 않습니다(`next_attempt_at = NULL`). 두 서비스의 배포가
  어긋났을 때 같은 공고를 반복 호출하지 않기 위해서입니다. 배포를 맞춘 뒤에는 공고 내용이 바뀔 때 다시 분석되며, 바로
  다시 분석하려면 운영자가 해당 행의 `next_attempt_at`을 현재 시각으로 바꾸고 `attempt_count`를 `max-attempts`보다 작게
  둡니다.
- 저장된 `COMPLETED` 결과의 `analysis_version`이 기대 버전과 다르면(기존 v1 결과) 같은 `claimable` SQL 조각에서 바인딩한
  `expectedVersion`으로 다시 후보가 됩니다. 일일 한도 안에서 마감 임박 순으로 처리합니다.
- 이전 버전 결과를 다시 분석하는 동안과 그 재분석이 실패해도 이전 결과를 지우지 않고 계속 보여 줍니다. 실패는
  `FAILED`로 바꾸지 않고 다음 재시도 시각만 기록하며(실패 코드는 로그 `REANALYSIS_DEFERRED`로만 남음), 선점 때
  `next_attempt_at`을 실행권 만료 시각으로 두어 실행 중 프로세스가 사라져도 다시 고릅니다. 최대 시도 횟수를 넘기거나
  재시도하지 않는 실패(버전 불일치 등)면 이전 버전 결과를 유지한 채 더 고르지 않습니다.
- 저장된 v1 JSON은 그대로 읽습니다. v2 목록·`sourceAttachmentNames`는 빈 목록, 근거의 `attachmentName`은 `null`로 읽습니다.

## 후보 선택과 실행권

1. 후보: `is_source_present = TRUE`이고 `application_end_date`가 없거나 서울 기준 오늘 이후인 공고 중, 분석 행이 없거나
   실행권이 비어 있으면서 (지문이 다름 | `PENDING`이고 시도 횟수 < `max-attempts` | `FAILED`이고 시도 횟수 < `max-attempts`
   이며 `next_attempt_at <= now` | `COMPLETED`이고 분석 버전이 기대 버전과 다르며 시도 횟수 0 또는 (시도 횟수 < `max-attempts`
   이고 `next_attempt_at <= now`))인 공고를 마감 임박 순(마감일 없음은 마지막)으로 하나 고릅니다. 잠금 없이 읽습니다.
2. 같은 transaction에서 새 공고면 `PENDING` 행을 만들고(`ON DUPLICATE KEY UPDATE`로 기존 행 유지), 같은 후보 조건
   (`claimable` SQL 조각)으로 그 행을 `FOR UPDATE`로 다시 읽습니다. 다른 Worker가 먼저 선점했으면 이번 실행은 건너뜁니다.
3. 지문이 바뀌었으면 이전 결과·실패 코드·시도 횟수를 비우고 `PENDING`으로 되돌립니다. 이어서 시도 횟수를 1 올리고
   `last_attempt_at`, 새 UUID `lease_token`, `lease_until`을 기록합니다.

`PENDING`을 별도 상태로 둔 이유: 새 행이 CHECK 제약(`COMPLETED`면 JSON 필수, `FAILED`면 실패 코드 필수)을 만족하면서
결과 없이 실행권만 가질 수 있어야 하기 때문입니다. 시도 횟수는 선점 시 올리므로 AI 호출 뒤 프로세스가 죽은 경우도
횟수에 포함되며, 실행권 만료 뒤 `max-attempts`까지만 다시 선택됩니다.

## 결과 저장과 재시도

- 성공: `COMPLETED`, `analysis_json`(Repository가 ObjectMapper로 직렬화), 버전·모델, `analyzed_at`, 시도 횟수 0, 실행권 해제.
- 실패: `FAILED`, `failure_code`, 실행권 해제. 다음 시도는 `1시간 × 2^(시도 횟수-1)`, 최대 24시간 뒤입니다.

| 실패 코드 | 원인 | 재시도 |
|---|---|---|
| `AI_UNAVAILABLE` | AI Service 503·연결 실패 | 예 |
| `AI_TIMEOUT` | 504·408·읽기 시간 초과 | 예 |
| `AI_UPSTREAM_ERROR` | 그 밖의 HTTP 오류 | 예 |
| `AI_INVALID_RESPONSE` | 응답 형식·열거값·길이·근거 필드 검증 실패 | 예 |
| `AI_REQUEST_REJECTED` | AI Service 422(요청 검증 거부) | 아니요(`next_attempt_at = NULL`, 공고가 바뀌면 다시 분석) |
| `AI_VERSION_MISMATCH` | 응답 `analysisVersion`이 기대 버전과 다름 | 아니요(`next_attempt_at = NULL`) |
| `SOURCE_UNAVAILABLE` | 기업마당 원문 수집 실패, 첨부 목록·파일 연결 실패·시간 초과 | 예 |
| `PROGRAM_NOT_PRESENT` | 선점 직후 공고가 비노출됨 | 아니요 |

결과 저장은 `lease_token`이 같을 때만 반영합니다. 실행권이 만료되어 다른 Worker가 다시 선점했다면 늦게 끝난 실행의
결과는 버립니다(`LEASE_LOST` 로그). 예상하지 못한 오류(DB 오류 등)는 감추지 않고 전파하며 Worker는 오류 종류만 기록합니다.
로그에는 결과 코드·시도 횟수·조건 수·폐기 항목 수만 남기고 공고 원문이나 모델 출력은 남기지 않습니다.

## 비용 제어

| 설정 | 환경변수 | 기본값 |
|---|---|---|
| `app.support-program-analysis.enabled` | `SUPPORT_PROGRAM_ANALYSIS_ENABLED` | `false` |
| `app.support-program-analysis.delay-ms` | `SUPPORT_PROGRAM_ANALYSIS_DELAY_MS` | `30000` |
| `app.support-program-analysis.daily-limit` | `SUPPORT_PROGRAM_ANALYSIS_DAILY_LIMIT` | `200` |
| `app.support-program-analysis.lease-seconds` | `SUPPORT_PROGRAM_ANALYSIS_LEASE_SECONDS` | `300` |
| `app.support-program-analysis.max-attempts` | `SUPPORT_PROGRAM_ANALYSIS_MAX_ATTEMPTS` | `3` |
| `app.ai-service.support-program-analysis-read-timeout` | `AI_SUPPORT_PROGRAM_ANALYSIS_READ_TIMEOUT` | `120s` |

- Worker와 전용 scheduler는 `enabled=true`일 때만 만들어집니다. `catalog-sync-once` 프로필은 이 값을 강제로 끕니다.
- 일일 한도는 서울 날짜 0시 이후 `last_attempt_at`이 기록된 **공고 수**입니다. 같은 공고의 같은 날 재시도는 한 번으로
  셉니다. 따라서 하루 AI 호출 수의 이론적 상한은 `daily-limit × max-attempts`입니다. 여러 인스턴스가 동시에 한도 직전에
  확인하면 인스턴스 수만큼 초과할 수 있습니다.
- v2부터 호출당 입력이 커집니다(첨부 본문 최대 40,000자 추가, 상세 원문 30,000자와 합쳐 공고당 최대 약 10만 자).
  추출 항목도 늘어 출력 토큰과 모델 시간이 증가하므로 호출당 비용이 v1보다 큽니다. 기존 v1 결과의 재분석도 같은 일일
  한도를 쓰므로 전환 기간에는 신규 공고 분석이 늦어질 수 있습니다.
- 첨부 수집은 공고마다 공식 첨부를 다시 내려받습니다(파일당 16MB, 공고당 32MB 한도). 첨부 원문은 저장하지 않습니다.

## 상세 응답

`GET /api/v1/support-programs/detail`의 `analysis`는 항상 포함됩니다. `COMPLETED`가 아니면 내용 필드는 `null` 또는 빈 목록입니다.

```json
"analysis": {
  "status": "COMPLETED",
  "analyzedAt": "2026-10-01T10:00:00",
  "summaryLine": "서울 AI 기업에 최대 5천만원을 지원합니다.",
  "supportTypes": ["GRANT", "RND"],
  "supportAmount": {
    "text": "최대 5천만원",
    "maxAmountKrw": 50000000,
    "evidence": { "field": "DETAIL_TEXT", "quote": "최대 5천만원 지원", "attachmentName": null }
  },
  "selectionScale": null,
  "conditions": [
    {
      "kind": "REQUIRED",
      "category": "BUSINESS_AGE",
      "text": "창업 7년 이내",
      "values": { "regions": null, "minYears": null, "maxYears": 7.0, "minAge": null, "maxAge": null },
      "evidence": { "field": "TARGET_DESCRIPTION", "quote": "창업 7년 이내 중소기업", "attachmentName": null }
    }
  ],
  "contact": { "text": "02-000-0000", "evidence": { "field": "DETAIL_TEXT", "quote": "문의 02-000-0000", "attachmentName": null } },
  "requiredDocuments": [
    {
      "name": "사업계획서",
      "requirement": "REQUIRED",
      "note": "지정 양식",
      "evidence": { "field": "ATTACHMENT", "quote": "사업계획서 1부", "attachmentName": "2026 모집공고.hwp" }
    }
  ],
  "selectionSteps": [
    { "name": "서류평가", "note": null, "evidence": { "field": "ATTACHMENT", "quote": "서류평가", "attachmentName": "2026 모집공고.hwp" } }
  ],
  "evaluationCriteria": [
    { "item": "기술성", "points": 40.0, "evidence": { "field": "ATTACHMENT", "quote": "기술성 40점", "attachmentName": "2026 모집공고.hwp" } }
  ],
  "schedule": [
    { "label": "접수 마감", "date": "2026-10-31", "text": "10월 31일 18시", "evidence": { "field": "DETAIL_TEXT", "quote": "10월 31일 18시까지", "attachmentName": null } }
  ],
  "sourceAttachmentNames": ["2026 모집공고.hwp"]
}
```

- `status`: `COMPLETED` | `FAILED` | `NOT_ANALYZED`
- `analyzedAt`: 서울 현지 시각 `yyyy-MM-ddTHH:mm:ss`(오프셋 없음), `COMPLETED`가 아니면 `null`
- `supportTypes`: `GRANT`, `LOAN`, `GUARANTEE`, `VOUCHER`, `CONSULTING`, `EDUCATION`, `SPACE`, `MARKETING`, `RND`, `EXPORT`, `HR`, `OTHER`
- `conditions[].kind`: `REQUIRED` | `EXCLUDED` | `PREFERRED`
- `conditions[].category`: `REGION`, `BUSINESS_AGE`, `FOUNDER_AGE`, `INDUSTRY`, `COMPANY_SIZE`, `LEGAL_FORM`, `CERTIFICATION`, `OTHER`
- `values.regions`: 17개 시·도 약칭 또는 `["전국"]`, `minYears`·`maxYears`는 실수, `minAge`·`maxAge`는 정수
- `evidence.field`: `SUMMARY` | `TARGET_DESCRIPTION` | `APPLICATION_METHOD` | `DETAIL_TEXT` | `ATTACHMENT`
- `evidence.attachmentName`: 항상 포함. `ATTACHMENT`일 때 보낸 첨부 이름, 그 밖에는 `null`
- `requiredDocuments[].requirement`: `REQUIRED` | `OPTIONAL` | `CONDITIONAL`(해당자만 제출), `note`는 nullable
- `selectionSteps`: 목록 순서가 절차 순서, `note`는 nullable
- `evaluationCriteria[].points`: JSON 숫자(실수) 또는 `null`
- `schedule[].date`: `YYYY-MM-DD` 또는 `null`(원문에 완전한 날짜가 없을 때). `text`는 원문 표현
- `requiredDocuments`·`selectionSteps`·`evaluationCriteria`·`schedule`·`sourceAttachmentNames`는 항상 포함되며
  `COMPLETED`가 아니거나 v1 분석이면 빈 배열입니다. `sourceAttachmentNames`는 분석에 실제로 보낸 첨부 이름입니다.

`analysisVersion`·`model`·`discardedItemCount`는 저장·로그용이며 공개 응답에 포함하지 않습니다. 목록 `analysisSummary`와
내 기업 조건 확인 응답은 v2에서도 바뀌지 않습니다.

## 목록·검색 카드 요약

검색(`GET`·`POST /api/v1/support-programs/search`), 로그인 후 복원(`POST …/search/results`), 직접 필터 목록
(`GET …/catalog`)의 각 공고에는 nullable `analysisSummary`가 있습니다. 현재 공고 내용과 지문이 같은 `COMPLETED`
분석이 있을 때만 채우고, 그 밖(`FAILED`·`PENDING`·지문 불일치·분석 없음·비노출 공고)에는 `null`입니다.

```json
"analysisSummary": {
  "summaryLine": "서울 AI 기업에 최대 5천만원을 지원합니다.",
  "supportAmountText": "최대 5천만원",
  "maxAmountKrw": 50000000,
  "supportTypes": ["GRANT", "RND"]
}
```

- `summaryLine`·`supportAmountText`·`maxAmountKrw`는 분석에 없으면 각각 `null`, `supportTypes`는 빈 배열일 수 있습니다.
  근거 인용과 조건은 싣지 않으므로 화면은 상세의 `analysis`와 공식 원문으로 확인을 안내합니다.
- 조회: 응답에 싣는 공고가 정해진 뒤 Service가 `SupportProgramAnalysisRepository.findCurrentSummaries`로 한 번만
  읽습니다(`(source_code, source_program_id) IN ((…), …)`, 공고당 조회 없음). 순위 계산·AI 호출 안에서는 읽지 않습니다.
  - 검색: 비회원 미리보기는 공개하는 앞 2건만, 회원은 반환하는 최대 5건만 조회합니다. 잠긴 공고의 분석은 읽지 않습니다.
  - 복원: Redis 스냅샷에는 공고만 저장하고 요약은 넣지 않습니다. 복원할 때 현재 분석을 다시 읽으므로 30분 사이에
    분석이 완료·변경되면 복원 응답에 반영됩니다. 스냅샷의 공고 본문은 검색 당시 값이므로 드물게 카드 본문과
    요약의 기준 시점이 다를 수 있습니다(요약은 항상 현재 DB 공고 기준).
  - 목록: Core는 목록을 캐시하지 않고 요청마다 공개 DB 스냅샷을 읽습니다. 페이지를 자른 뒤 그 페이지 공고(최대 50건)만 조회합니다.
- 관심 공고함(`/api/v1/me/saved-programs`)도 같은 공고 응답 형태를 쓰지만 요약을 싣지 않아 항상 `null`입니다.
  상세 응답에는 `analysisSummary` 필드가 없습니다(`analysis` 전체를 제공).
- 요약 조회의 DB 오류는 감추지 않고 검색·목록 요청의 오류로 전파합니다.

## 내 기업 조건 확인

`GET /api/v1/me/support-programs/condition-check?sourceCode=&sourceProgramId=`는 로그인한 회원의 기업 프로필
(소재지·설립연도)로 현재 완료 분석의 조건을 하나씩 비교합니다. 세션이 없으면 401, 없는·미노출 공고는 상세와 같은
404 `SUPPORT_PROGRAM_NOT_FOUND`이며 응답은 `Cache-Control: no-store`입니다. AI를 호출하거나 결과를 저장하지 않습니다.

```text
SupportProgramConditionCheckController → SupportProgramConditionCheckService
  → SupportProgramRepository(공고 404 확인)·SupportProgramAnalysisRepository.findCurrent·CompanyRepository.findByAccountId
  → SupportProgramConditionCheck(순수 도메인 규칙, 서울 기준 오늘 날짜)
```

```json
{
  "status": "CHECKED",
  "analyzedAt": "2026-10-01T10:00:00",
  "referenceDate": "2026-10-01",
  "profile": { "region": "서울", "foundedYear": 2019 },
  "overall": "UNKNOWN",
  "conditions": [
    { "index": 0, "result": "MET", "reason": "REGION_MATCH" },
    { "index": 1, "result": "UNKNOWN", "reason": "BOUNDARY_YEAR" },
    { "index": 2, "result": "MET", "reason": "PRE_STARTUP_ONLY" },
    { "index": 3, "result": "UNKNOWN", "reason": "NOT_COMPARABLE" }
  ]
}
```

| `status` | 조건 | `analyzedAt` | `profile` | `overall`·`conditions` |
|---|---|---|---|---|
| `NOT_ANALYZED` | 현재 공고 내용 기준 `COMPLETED` 분석이 없음(`FAILED` 포함). 기업 유무보다 우선 | `null` | 기업이 있으면 값, 없으면 `null` | `null`·`[]` |
| `NO_COMPANY` | 완료 분석은 있으나 계정에 기업이 없음 | 분석 시각 | `null` | `null`·`[]` |
| `CHECKED` | 완료 분석과 기업이 모두 있음 | 분석 시각 | 값 | 조건별 결과 |

- `analyzedAt`은 상세 `analysis.analyzedAt`과 같은 형식이며, 이 확인에 쓴 분석입니다. `referenceDate`는 서울 기준 오늘입니다.
- `conditions[].index`는 같은 시점 상세 `analysis.conditions`의 위치이며 순서도 같습니다. 두 요청 사이에 공고가 바뀌어
  분석이 달라질 수 있으므로 화면은 `analyzedAt`이 상세와 같은지 확인할 수 있습니다.
- `profile.region`은 프로필 소재지를 17개 시·도 약칭으로 바꾼 값입니다. 기업 프로필은 화면 선택지의 정식 명칭
  (`서울특별시`·`경기도`·`강원특별자치도`·`전북특별자치도` 등)으로 저장되며, 이전 명칭(`강원도`·`전라북도`·`제주도`)과 약칭도
  인식합니다. 알 수 없는 값이면 `null`이고 지역 조건은 `PROFILE_MISSING`입니다.

### 조건별 판정

조건의 `values`는 그 조건이 말하는 집단이며, 먼저 기업이 그 집단에 속하는지(예·아니요·알 수 없음)를 정합니다.

| 분류 | 판정 | 근거 코드 |
|---|---|---|
| `REGION` | `regions`가 없거나 비면 알 수 없음. 프로필 지역이 없으면 알 수 없음. `regions`에 `전국`이 있거나 기업 약칭이 있으면 예, 아니면 아니요. 약칭으로 바꿀 수 없는 조건 지역이 섞이면 알 수 없음 | `NOT_COMPARABLE`·`PROFILE_MISSING`·`REGION_MATCH`·`REGION_MISMATCH` |
| `BUSINESS_AGE` | 아래 업력 규칙 | `NOT_COMPARABLE`·`PRE_STARTUP_ONLY`·`PROFILE_MISSING`·`BUSINESS_AGE_WITHIN`·`BUSINESS_AGE_OUTSIDE`·`BOUNDARY_YEAR` |
| `FOUNDER_AGE` | 프로필에 대표자 나이가 없으므로 알 수 없음(나이 값 자체가 없으면 `NOT_COMPARABLE`) | `PROFILE_MISSING`·`NOT_COMPARABLE` |
| `INDUSTRY`·`COMPANY_SIZE`·`LEGAL_FORM`·`CERTIFICATION`·`OTHER` | 자유 문장을 추측하지 않고 항상 알 수 없음 | `NOT_COMPARABLE` |

업력은 설립연도만 알므로 가능한 설립일을 그해 1월 1일~12월 31일로 봅니다.

- `maxYears` m: 기준일에서 m년을 뺀 날(경계일 포함) 이후 설립이어야 합니다. 소수 연수는 `round(m × 12)`개월로 바꿉니다.
  그해의 모든 날짜가 경계일 이후면 예, 모두 이전이면 아니요, 섞이면 `BOUNDARY_YEAR`입니다.
- `minYears` n: 기준일에서 n년을 뺀 날(경계일 포함) 이전 설립이어야 하며 같은 방식으로 판정합니다.
- 둘 다 있으면 하나라도 아니요면 아니요, 하나라도 알 수 없으면 알 수 없음, 그 밖에는 예입니다.
- `maxYears = 0`(예비창업자 전용)은 기업을 등록한 계정이므로 설립연도와 관계없이 아니요(`PRE_STARTUP_ONLY`)입니다.
- 값이 없으면 `NOT_COMPARABLE`, 설립연도가 없으면 `PROFILE_MISSING`입니다.

예: 기준일 2026-10-01, "창업 7년 이내"(`maxYears = 7`) → 경계일 2019-10-01. 2020년 설립은 `MET`, 2019년은 `UNKNOWN`
(`BOUNDARY_YEAR`), 2018년은 `NOT_MET`입니다.

집단 소속을 조건 종류에 따라 `result`로 바꿉니다. 근거 코드는 소속 여부를 그대로 나타냅니다.

| 종류 | 예 | 아니요 | 알 수 없음 |
|---|---|---|---|
| `REQUIRED` | `MET` | `NOT_MET` | `UNKNOWN` |
| `EXCLUDED` | `NOT_MET`(제외 대상) | `MET` | `UNKNOWN` |
| `PREFERRED` | `MET`(우대 해당) | `NOT_MET`(우대 미해당) | `UNKNOWN` |

`overall`은 `REQUIRED`·`EXCLUDED`만 봅니다. 하나라도 `NOT_MET`이면 `NOT_MET`, 아니면 하나라도 `UNKNOWN`이면 `UNKNOWN`,
셀 조건이 하나 이상이고 모두 `MET`이면 `MET`, 셀 조건이 없으면(조건이 없거나 우대만 있음) `UNKNOWN`입니다. `PREFERRED`는
전체 판정에 영향을 주지 않습니다. 이 결과는 AI 추출 조건과 프로필 두 항목만 비교한 참고 정보이며 신청 자격이나 선정을
확정하지 않습니다.

## 알려진 한계

- 첨부는 공식 상세가 직접 연결한 PDF/HWP/HWPX/DOCX/XLSX 중 텍스트를 추출할 수 있는 것만 씁니다. 스캔 PDF(OCR)·ZIP·
  이미지·암호화 문서는 제외되고, 본문 합계 40,000자를 넘는 부분은 잘립니다. 기존 Client 한도(기업마당 첨부 4개 초과 등)를
  넘는 공고는 첨부 없이 분석합니다.
- 상세 HTML 원문(`detailText`)은 기업마당만 보냅니다. 다른 제공처는 API 필드와 첨부만 분석합니다.
- 첨부 수집 분기(제공처별 Client 선택)는 신청 양식 발견·중복 지원 검토 Service에도 같은 형태로 있습니다. 이번 변경에서는
  기존 두 Service를 새 Facade로 옮기지 않았습니다.
- 분석 결과는 AI 추출이며 사람이 검토한 정답이 아닙니다. 화면은 근거 인용과 공식 원문 확인 안내를 함께 보여야 합니다.
- 후보 선택은 매 실행마다 현재 공고 전체의 지문을 계산합니다. 공고 수가 크게 늘면 조회 비용을 다시 확인해야 합니다.
- 최대 시도 횟수를 넘긴 `FAILED`·`PENDING` 행은 공고 내용이 바뀔 때까지 다시 분석하지 않습니다.
- 조건 확인은 소재지·설립연도만 비교합니다. 업종·기업 규모·법인 형태·인증·대표자 나이는 항상 `UNKNOWN`입니다.
