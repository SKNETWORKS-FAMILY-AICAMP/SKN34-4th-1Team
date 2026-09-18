# GovBiz — SKN34 4차 프로젝트

[3차 GovBiz](https://github.com/SKNETWORKS-FAMILY-AICAMP/SKN34-3rd-1Team)를 기반으로 개발하는 **별도 저장소의 Django 서비스**입니다.

현재 범위는 Django 기본 골격과 로컬 개발 환경입니다. 공고·회원·신청 관리 중 어떤 업무를 이전할지는 아직 확정하지 않았습니다. 기존 Spring Boot/FastAPI의 업무 코드나 운영 데이터는 이전하지 않았습니다.

## 기술 구성

- Python 3.13
- Django 5.2 LTS, Django REST Framework
- MySQL 8.4, `utf8mb4`
- uv 0.12.5와 `uv.lock`을 통한 의존성 고정
- Ruff, Django 테스트 러너
- Docker Compose, GitHub Actions CI

Django 기본 사용자 테이블과 관리자 화면은 아직 추가하지 않았습니다. 인증 방식과 기존 GovBiz 계정의 연동 범위를 정한 뒤 도입합니다. 업무 API의 기본 권한은 `IsAuthenticated`이며, 현재 상태 확인 API만 공개합니다.

## 빠른 시작 — Docker

Docker Desktop의 Linux 컨테이너 엔진이 실행되어 있어야 합니다.

PowerShell:

```powershell
Copy-Item .env.example .env
docker compose up --build --detach --wait --wait-timeout 180
```

Linux/macOS에서는 첫 명령을 `cp .env.example .env`로 실행합니다. 이미 `.env`를 설정했다면 복사 단계는 건너뜁니다.

- 실행 확인: [http://127.0.0.1:8001/api/v1/health](http://127.0.0.1:8001/api/v1/health)
- DB 연결 확인: [http://127.0.0.1:8001/api/v1/health/ready](http://127.0.0.1:8001/api/v1/health/ready)
- MySQL: `127.0.0.1:3308`, DB/사용자 `govbiz4`
- Compose 프로젝트: `govbiz4-django`
- 데이터 볼륨: 이 프로젝트의 `mysql-data`

기존 3차 프로젝트의 컨테이너·네트워크·DB 볼륨과 분리됩니다. `config/`, `apps/`, `manage.py`를 컨테이너에 연결하므로 Python 코드 변경은 개발 서버에 반영됩니다. 의존성을 변경하면 이미지를 다시 빌드합니다.

```powershell
docker compose logs --follow web
docker compose down
```

`down`은 데이터 볼륨을 유지합니다. 현재 Docker 이미지는 개발 서버를 실행하며 운영 배포 설정은 포함하지 않습니다.

## Python을 호스트에서 실행

[uv 공식 설치 안내](https://docs.astral.sh/uv/getting-started/installation/)에 따라 uv 0.12.5와 Python 3.13을 준비합니다. 기존 uv는 요구 버전에 맞춥니다.

```powershell
Copy-Item .env.example .env
uv sync --locked
docker compose up --detach db --wait
uv run --locked python manage.py check
uv run --locked python manage.py migrate
uv run --locked python manage.py runserver 127.0.0.1:8001
```

호스트에서 실행할 때는 Compose의 `web`을 동시에 실행하지 않습니다. 이미 켜져 있다면 `docker compose stop web`을 먼저 실행합니다. Linux에서 mysqlclient 빌드 도구가 없다면 `default-libmysqlclient-dev`, `build-essential`, `pkg-config`를 설치하거나 Docker 실행 경로를 사용합니다.

## API

| 경로 | 성공 응답 | 실패 동작 |
| --- | --- | --- |
| `GET /api/v1/health` | `200`, `status: UP` | DB를 호출하지 않음 |
| `GET /api/v1/health/ready` | `200`, `database: UP` | MySQL 연결/질의 실패 시 `503`, 내부 연결 정보는 응답에 노출하지 않음 |

URL 끝에 슬래시를 붙이지 않습니다. 상태 확인 경로는 쓰기 요청을 받지 않습니다.

호출 흐름은 `HTTP → Django URL → DRF View → JSON`이며, readiness만 MySQL에서 `SELECT 1`을 실행합니다. 현재 외부 AI API 호출은 없습니다.

## 검증

로컬 MySQL을 실행한 뒤 다음 명령을 사용합니다.

```powershell
uv sync --locked
uv run --locked ruff check .
uv run --locked ruff format --check .
uv run --locked python manage.py check
uv run --locked python manage.py makemigrations --check --dry-run
uv run --locked python manage.py test --noinput
```

Docker 안에서도 테스트할 수 있습니다.

```powershell
docker compose exec -T web python manage.py test --noinput
```

테스트 러너는 별도 `test_govbiz4` DB를 생성·삭제합니다. Compose의 최초 DB 초기화 SQL은 개발 사용자에게 그 DB의 권한만 추가로 부여합니다. 테스트는 실제 MySQL 연결, DB 장애 시 503 응답, liveness의 DB 비의존성, HTTP 메서드 제한, 허용 호스트를 확인합니다.

GitHub Actions는 Ruff·Django 검사·MySQL 테스트와 Docker 빌드·상태 확인을 수행합니다. 실제 GitHub CI 실행은 파일을 원격 저장소에 올린 뒤 확인할 수 있습니다.

## 디렉터리

```text
config/                  Django 설정·URL·WSGI·ASGI
apps/health/             실행/DB 연결 상태 API 및 테스트
infrastructure/mysql/    개발용 테스트 DB 초기화
.github/workflows/       CI
manage.py                관리 명령 진입점
pyproject.toml           Python 의존성과 개발 도구 설정
uv.lock                  확정된 의존성
Dockerfile               개발용 이미지
compose.yaml             Django·MySQL 로컬 환경
.env.example             로컬 환경변수 예시
```

## 환경변수와 기존 프로젝트 연결

`.env`는 Git/Docker 빌드 컨텍스트에서 제외됩니다. `.env.example`의 비밀번호와 비밀 키는 로컬 개발용입니다. 실제 환경변수가 `.env`보다 우선합니다.

- `DJANGO_SECRET_KEY`, `DB_PASSWORD`: 필수
- `DJANGO_DEBUG`: 기본 `false`; 예시 파일은 로컬 개발용 `true`
- `DJANGO_ALLOWED_HOSTS`: 쉼표로 구분하는 허용 호스트
- `DB_HOST`, `DB_PORT`: 호스트 실행 기본값 `127.0.0.1:3308`
- `API_PORT`, `MYSQL_PORT`: Compose가 호스트에 공개하는 포트
- `MYSQL_ROOT_PASSWORD`: 개발용 MySQL 초기화 비밀번호

Compose의 DB 이름/사용자는 `govbiz4`로 고정하여 테스트 초기화 SQL과 일치시킵니다. 포트를 변경하면 호스트 실행의 `DB_PORT`도 맞춰야 합니다.

향후 Django가 담당할 업무를 확정한 뒤 기존 React·Spring Boot·FastAPI와 HTTP 또는 메시지 계약으로 연결합니다. 같은 테이블을 Spring의 Flyway와 Django migration이 동시에 관리하지 않도록 데이터 소유권을 먼저 정합니다.
