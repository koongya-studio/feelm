# S15P21E106-222 로컬 manager 백엔드 입력 준비

상태: 입력 확보/검사 완료. 후속 [새 manager backend 실행](LOCAL-BACKEND-RUN.md)에서
82개와 spotlessCheck 재검증까지 통과했다. 아래는 입력 준비 단계의 기록이다.
서버·Runner·CI 변경, Docker pull, Gradle 실행 및 Java 설치는 수행하지 않았다.

## 실제 확인한 사항

- 로컬 WSL에는 Java 11만 발견됐다. Java 21, 서버의 검증 캐시·이미지 입력은 확보되지 않았다.
- 검토 revision `9631afd9c616e047abbdd0ad7bf8192231f7f35c`의 backend를 Git 객체에서
  추출했다. 작업 트리나 최신 브랜치의 파일을 대신 사용하지 않는다.
- 소스 SHA256 `b045a76f85d8373dec543a58ec1a72e8aacebe288d0856bbfe678db8d5c4d3b1`이
  이전 서버 검증과 일치한다. 출력은 Windows 임시 폴더의 `ci222-reviewed-backend-9631afd.tar`.
- 단위 테스트 146개 중 145개 통과/1개 환경 조건으로 건너뜀. 이 수치는 backend 테스트가 아니다.

## 검사기 계약

`ci-local-backend-inputs.py` 기본 실행은 계획만 출력한다.
`export-source --output <새 파일>`은 고정 revision과 SHA를 검사하고 기존 파일을 덮어쓰지 않는다.
`inspect --bundle <폴더> --cache-manifest-sha256 <검토한 SHA> --jdk-sha256 <공식 출처로 확인한 SHA>`는
아래 자료만 있는 폴더를 검사한다. run 동작이나 manager 자동 실행 기능은 없다.

| 이름 | 검사 내용 |
| --- | --- |
| backend.tar | 고정 revision의 SHA, 경로·링크·크기 제한 |
| backend-images.tar | 이전 서버 전송본 SHA f1fdc5c9e4e3c96d1d04f4bdab407e1953764199ad2e2a7b007c50c0810990cb |
| cache-manifest.json | 별도로 확인한 SHA, 소스 revision/SHA, cache 항목 |
| gradle-cache/ | caches 및 wrapper/dists의 기록된 파일·SHA·크기 일치; 링크·추가 설정·lock 거부 |
| jdk.tar.gz | 별도로 승인한 Linux x86_64 JDK21 배포본의 SHA 및 크기 |

JDK 해시 일치만으로 Java 21 런타임 호환성을 주장하지 않는다. 이미지 tar의 해시 일치도
실제 daemon에 import 후 image ID/digest를 검사하는 단계를 대신하지 않는다.
캐시 manifest의 SHA는 복사한 파일에서 임의로 계산해 신뢰 근거로 삼지 않고,
검토된 원본에서 별도로 확인한 값을 사용해야 한다. 이 도구는 신뢰되지 않는 입력용 보안 제품이 아니다.

## 아직 필요한 입력 및 후속 구현

서버 자료는 이전 대화에 나온 경로이며 이번에 실시간 존재 여부를 확인하지 않았다.

- Gradle 캐시: `/home/feelm-ci-test/backend-checks/ee737a8331104a619b767a40ae996129/home/.gradle`
- 준비 당시 manifest: `/home/feelm-ci-test/offline-inputs/9627ce2a9b774f11a71743bcfb8aec93/manifest.json`
- 이미지: `/home/feelm-ci-test/ci222-backend-images-E4nFHV/backend-images.tar`

서버의 전체 home, `.env`, `.docker/config.json`, Runner 설정이나 운영 볼륨은 복사하지 않는다.
캐시 원본 전체를 무조건 옮기지 않고 manifest에 기록된 파일만 복사해야 한다.
서버→로컬 전송 방법 합의와 Linux JDK21 배포본 확보 이후 다음을 구현/검증한다.

1. 새 임시 rootfs에 소스·선별 캐시·Java·이미지를 복사. 호스트 경로 bind나 온라인 fallback 금지.
2. 격리 내부에서 Java21 확인, 이미지 ID 검사, 내부 Testcontainers 통신/포트 검사.
3. fresh home·offline 옵션으로 `clean test spotlessCheck`, 82개/skip0/필수 suite 및 Gradle 종료코드 확인.
4. 정상/취소/timeout 후 scope·프로세스·컨테이너 정리 검사, 증빙 보존.

최종 채택/서버 설치/실제 MR 전환은 별도 승인이다.

## 승인 후 입력 전송 완료 (2026-09-05)

사용자가 테스트 캐시·이미지의 로컬 전송과 Java 21 준비를 승인했다.
기존 `e106` SSH 별칭과 `StrictHostKeyChecking=yes`로 서비스 서버
`ip-172-26-0-186`을 확인했다. 원격 스크립트는 위 고정 manifest에 적힌
캐시 파일과 이미지 tar만 읽었고, 원격 임시 파일·권한·설정을 변경하지 않았다.
개인키 원문은 읽거나 출력하지 않았다.

`ci-local-backend-transfer.py` 기본 실행은 계획만 출력한다.
`--confirm-reviewed-transfer`는 선택 파일을 전송하고 공식 JDK 배포본을 다운로드한다.
이미 전송된 파일은 `--reuse-selected-archive`와 기존 원격 manifest SHA로 재사용할 수 있다.
초기 Windows 긴 경로 제한으로 추출이 중단됐으나 원본 전송 tar는 보존했고,
확장 경로로 새 폴더에 추출해 재검사했다. 실패한 부분 폴더를 덮어쓰거나 삭제하지 않았다.

검증 완료 bundle:
`C:/Users/SSAFY/AppData/Local/Temp/ci222-backend-inputs-qg837s4d/bundle`

증빙: 상위 폴더의 `transfer-evidence.json`.

- 캐시: 2,986개, 406,181,461 bytes. manifest에 기록된 모든 SHA 일치.
- 원격 manifest SHA: `126fcba198a358f22c7108c652cfaaa44ff7f32eb4b9d36a3767ec91953b927e`
- 소스와 이미지 tar SHA: 위의 고정 검증값과 일치.
- [공식 Temurin Linux x64 JDK 21.0.12.1+1](https://github.com/adoptium/temurin21-binaries/releases/tag/jdk-21.0.12.1%2B1)
  배포본 207,473,347 bytes. Adoptium API에서 제공한 SHA와 다운로드 파일 일치:
  `ce79869e1307ed8ee1e2baa86a412b1eb5b75d10a01006d788a6f968bcfaee94`
- 별도 서명 검증은 하지 않았다. Java 설치/실행 및 새 manager 내부의 런타임 호환성은 미검증이다.
- 단위 테스트 149개 중 148개 통과, 1개 건너뜀. backend 82개는 아직 실행하지 않았다.

현재 입력 확보 차단은 해소됐다. 다음은 새 manager의 rootfs에 입력을 복사하고
Java 런타임·이미지 import·내부 통신을 확인한 뒤 backend 검사와 종료 정리를 실행하는 단계다.
서버의 기존 backend 결과나 입력 해시 통과를 새 환경의 테스트 통과로 간주하지 않는다.
다운로드·압축 전송본과 첫 실패 추출본은 로컬 임시 폴더에 보존돼 추가 디스크를 사용한다.
