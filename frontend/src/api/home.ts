/*
 * 2.1 `내 OTT에서 지금 볼 수 있어요` 데이터 접근 함수.
 *
 * 대응하는 API가 없다(CR-14). 계약이 정해지면 이 파일을 지우거나 확정된 도메인
 * 파일로 옮긴다. 화면이 목업을 직접 import하지 않도록 함수 계층은 유지한다.
 */
import { mockAvailableNowMovies } from '../mocks/home';
import { respond } from './mockTransport';
import type { AvailableNowMovie } from '../types/unspecified';

/** 계약에 없음 (CR-14) */
export async function getAvailableNowMovies(): Promise<AvailableNowMovie[]> {
  return respond(mockAvailableNowMovies);
}
