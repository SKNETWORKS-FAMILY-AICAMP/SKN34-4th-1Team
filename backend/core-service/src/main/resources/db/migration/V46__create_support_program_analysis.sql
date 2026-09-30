-- 공고별 AI 분석(지원 형태·금액·조건 등) 결과와 백그라운드 분석 실행권을 저장한다.
-- 공고 행은 동기화에서 UPSERT·비노출 처리만 되고 삭제되지 않으므로 연쇄 삭제 없는 FK로 참조 무결성을 지킨다.
-- FK를 위해 식별자 컬럼은 support_program과 같은 utf8mb4_0900_ai_ci 비교 규칙을 사용한다.
CREATE TABLE support_program_analysis (
    source_code VARCHAR(64) NOT NULL,
    source_program_id VARCHAR(255) NOT NULL,
    -- 분석 입력 공고 내용의 SHA-256입니다. 계산식은 SupportProgramAnalysisMapper.xml 한 곳에서만 정의한다.
    program_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    -- PENDING은 실행권을 얻었지만 아직 결과가 없는 상태이며 상세 응답에는 NOT_ANALYZED로 보인다.
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    analysis_version VARCHAR(80) NULL,
    model VARCHAR(200) NULL,
    analysis_json JSON NULL,
    failure_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NULL,
    last_attempt_at DATETIME(6) NULL,
    lease_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lease_until DATETIME(6) NULL,
    analyzed_at DATETIME(6) NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (source_code, source_program_id),
    CONSTRAINT fk_support_program_analysis_program
        FOREIGN KEY (source_code, source_program_id)
        REFERENCES support_program (source_code, source_program_id),
    CONSTRAINT chk_support_program_analysis_status CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED')),
    CONSTRAINT chk_support_program_analysis_result CHECK (
        (status = 'COMPLETED' AND analysis_json IS NOT NULL AND JSON_TYPE(analysis_json) = 'OBJECT'
            AND analysis_version IS NOT NULL AND model IS NOT NULL AND analyzed_at IS NOT NULL)
        OR (status <> 'COMPLETED' AND analysis_json IS NULL)
    ),
    CONSTRAINT chk_support_program_analysis_failure CHECK (
        (status = 'FAILED' AND failure_code IS NOT NULL)
        OR (status <> 'FAILED' AND failure_code IS NULL)
    ),
    CONSTRAINT chk_support_program_analysis_lease CHECK ((lease_token IS NULL) = (lease_until IS NULL)),
    CONSTRAINT chk_support_program_analysis_attempt CHECK (attempt_count >= 0),
    INDEX idx_support_program_analysis_last_attempt (last_attempt_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
