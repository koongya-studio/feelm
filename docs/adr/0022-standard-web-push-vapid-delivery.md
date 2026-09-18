# ADR-0022: 표준 Web Push와 VAPID로 브라우저 알림을 전달한다

| 항목 | 값 |
|---|---|
| 상태 | 채택 |
| 결정일 | 2026-09-18 |
| 결정자 | S15P21E106-582 작업 승인 |
| 관련 | FR-41, API-075~077, ADR-0021, S15P21E106-582~583 |

## 맥락

ADR-0021의 PostgreSQL 인앱 알림 정본을 브라우저가 닫힌 상태에서도 전달해야 한다. 제공자별 SDK에 서버가 종속되지 않으면서 Chrome·Edge·Firefox·Safari가 발급하는 Push Service endpoint를 처리하고, 외부 서비스의 일시 장애나 만료 응답에도 알림 사건과 전송 상태를 분리해 복구할 수 있어야 한다.

## 결정

표준 Web Push 암호화와 VAPID 인증을 사용한다. 브라우저 구독 capability는 활성 상태에서만 PostgreSQL에 저장하고, 알림·canonical endpoint fingerprint별 durable delivery를 생성해 lease 기반 worker가 최대 5회 지수 재시도한다. worker는 배치 후보를 미리 잡아 두지 않고 실제 전송 직전에 한 행씩 claim하며, 구독 갱신마다 증가하는 capability version으로 이전 요청의 늦은 404·410 응답을 격리한다. `PARTY_INVITE`는 항상, `WATCH_CONFIRM`은 기존 `RATING` 알림 설정이 켜진 경우에만 전송한다.

VAPID 개인키는 배포 Secret으로만 주입한다. 구독 endpoint는 HTTPS 및 명시한 브라우저 Push Service 호스트 suffix만 허용하고, 해제·만료·탈퇴 시 endpoint와 구독 키 원문을 지운다.

## 고려한 대안

| 대안 | 장점 | 단점 |
|---|---|---|
| 표준 Web Push + VAPID + 자체 durable delivery (선택) | 브라우저 표준 사용, 제공자 SDK 비종속, 인앱 정본과 원자적 enqueue·감사 가능 | 키 관리와 재시도 worker 운영 필요 |
| Firebase Admin SDK에 서버 통합 | FCM 운영 도구 활용 | Firefox·Safari endpoint 처리와 별도 토큰 모델이 필요하고 제공자 결합 증가 |
| 요청 시 즉시 전송 | 저장 구조가 단순 | 도메인 트랜잭션 지연·외부 장애 전파·재시도 유실 |

## 결과

- **얻는 것**: 브라우저 제공자에 독립적인 구독 API, 중복 방지, 재시도와 만료 구독 자동 정리, 전송 메트릭
- **감수하는 것**: PostgreSQL polling worker 부하, VAPID key rotation 운영 절차, 허용 endpoint 목록 유지. 외부 Push Service 성공과 DB 완료 기록은 원자화할 수 없으므로 성공 직후 프로세스 중단 시 at-least-once 중복 전달 가능성이 남는다
- **후속 작업**: S15P21E106-583에서 서비스 워커 구독과 payload 클릭 라우팅을 연결하고 실제 브라우저별 smoke test를 수행한다
