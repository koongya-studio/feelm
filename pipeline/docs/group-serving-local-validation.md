# 8×128 그룹 서빙 번들 로컬 검증

## 범위

- Jira: S15P21E106-439
- 실행일: 2026-09-15
- 기준 코드: `origin/develop` 667a29bc4888b41d96e37c5def844f3511503b6c에서 분기한 작업본
- 검증 대상: `group-serving-artifacts-v1`, geometry `g913-gkt128-mean-v1`
- 제외: 현재 개발 DB export의 ID 안정성 대조, 실제 HDFS 권한·네트워크·복제, S15P21E106-415 추천 정책·Redis·Spring

원본은 `group-recipe.v1.json`에 고정된 DV2 r2 파일 5개의 size·SHA-256을 먼저 대조했다. pickle은
검증 전 열지 않았고, 변환에는 recipe와 같은 NumPy 1.26.4, SciPy 1.15.2, scikit-learn 1.9.0의
격리 환경을 사용했다. 서버용 Consumer 프로젝트에는 pickle loader와 scikit-learn을 포함하지 않았다.

## 결과

| 검증 | 결과 |
| --- | --- |
| 기준 assignment 행 | 237,817 |
| `taste_id` 불일치 | 0 |
| `child_id` 불일치 | 0 |
| `group_id` 불일치 | 0 |
| 단건 transform 층화 샘플 | 2,048행, 상·하위 ID 불일치 0 |
| 장르 미지원 | 30,577행, 모두 발견 추천 제외 |
| `group_mapped=true` | 207,240행 |
| Consumer 테스트 | 47 passed, 6 skipped |
| Ruff | 변경 Python 파일 PASS |
| 같은 버전 재생성 | `group version already exists`로 거부 |

최종 재생성한 로컬 기준 bundle의 버전은 `g913-gkt128-mean-v1-reference-20260915-r1`이며 5파일,
26,732,237 bytes다. 학습 객체가 아니라 안전 배열·assignment·lineage만 포함한다.

| 파일 | bytes | SHA-256 |
| --- | ---: | --- |
| `_SUCCESS` | 0 | `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` |
| `arrays.npz` | 12,716,996 | `967399d3d7f59149cfd657dd3fd27b08988c6d7315bd643687c108b341b99c7c` |
| `assignments.parquet` | 14,012,386 | `7b9ef6e091ebc30fc1cd719c05a6596562fbfa752d4127180d8b16a7156e0262` |
| `manifest.json` | 667 | 실행별 metadata 시각을 포함하므로 최종 서버 bundle에서 다시 기록 |
| `metadata.json` | 2,188 | 실행별 `built_at_utc`를 포함하므로 최종 서버 bundle에서 다시 기록 |

검증용 매핑은 원 연구 카탈로그의 ID쌍으로 만든
`mapping_revision=sha256:d91bc460cdb5d8ddc753a413a20b3570891b757c015d5b90a6e64b7538cc3de0`이며
현재 개발 DB revision이라고 주장하지 않는다. 재현용 mapping Parquet SHA-256은
`affa259ba4d6b4fc4e18fe91e723dca21adbdcbbb3148fecec137a3a9e2d5d12`다. 현재 DB에 영화가 추가되거나
삭제됐다는 이유만으로 이 bundle을 다시 학습하거나 폐기하지 않는다. 기존 service ID가 다른 TMDb 영화로
재결합되지 않았고 live export의 ID가 유일하면 기존 assignment를 사용할 수 있다. live에만 있는 새 영화는
같은 frozen geometry로 다음 불변 bundle을 만들기 전까지 발견 추천에서 제외한다.

## 전송 경계

기존 `jobs.batch.artifact_transfer manifest`로 위 5파일의 전체 inventory·bytes·SHA manifest 생성을
확인했다. Windows의 Hadoop local filesystem publish 테스트는 `winutils`/NativeIO 부재로 6개만 통과하고
7개가 환경 오류로 실행되지 않았다. 이는 Linux/HDFS rename 검증을 대체하지 않는다.

현재 작업 PC에서는 분산 노드 사설망 SSH가 timeout이고 SSH/HDFS 접속 설정도 없어 실제 HDFS 쓰기와
Consumer HDFS 재로딩을 수행하지 못했다. **복제 수 2·SHA 대조·원자적 공개·HDFS URI 재로딩은 미완료다.**
서버 접근이 가능한 환경에서 다음을 수행하기 전까지 HDFS 검증 완료나 service activation 상태로 표시하지 않는다.

1. 현재 DB export와 bundle ID쌍을 대조해 중복·기존 service ID 재결합이 0인지 확인하고 추가·삭제 건수를 기록한다. 전체 `mapping_revision` 불일치만으로 거부하지 않는다.
2. 신규 영화까지 발견 추천에 포함해야 할 때만 같은 frozen geometry로 새 불변 `group-version`을 생성해 로컬 reader로 재로딩한다.
3. 고유한 HDFS 검증 경로의 sibling staging에 5파일을 올린다.
4. 복제 수 2와 전체 SHA를 대조하고 `Rename.NONE`으로 최종 경로에 공개한다.
5. HDFS URI로 Consumer reader를 실행해 bundle revision·행 수·지원 건수를 확인한다.
