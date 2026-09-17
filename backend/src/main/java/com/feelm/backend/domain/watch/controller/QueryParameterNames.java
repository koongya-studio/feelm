package com.feelm.backend.domain.watch.controller;

import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import java.util.Iterator;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.web.context.request.WebRequest;

/**
 * 질의 파라미터 <b>이름</b>의 표기를 검사한다. 값 검증은 각 API의 조건 객체가 맡는다.
 *
 * <p>배열 조건은 {@code status=A&status=B}처럼 같은 이름을 반복해 전달하는 것만 계약이다. {@code status[0]}이나 {@code
 * status[].name} 같은 인덱스·중첩 표기는 Spring이 다른 이름의 파라미터로 보고 <b>조용히 버리기</b> 때문에, 거르지 않으면 필터가 걸린 줄 아는
 * 클라이언트가 거른 적 없는 전체 목록을 받는다(S15P21E106-492).
 *
 * <p>같은 판정식이 {@code MovieSearchController.validateSearchParameterNames}(API-028)에도 인라인으로 있다. 두 API의
 * 판정 기준은 같아야 하므로 한쪽만 고치지 않는다. 검색을 이쪽으로 모으는 것은 소유 도메인이 달라 이번 범위에서 제외했고, 그래서 아직 한 도메인만 쓰는 코드라 {@code
 * global}이 아니라 이 도메인 안에 둔다(백엔드 컨벤션 3장).
 */
final class QueryParameterNames {

    private QueryParameterNames() {}

    /**
     * 인덱스·중첩 표기 이름이 하나라도 있으면 {@link ErrorCode#VALIDATION_FAILED}로 거절한다.
     *
     * <p>선언한 파라미터뿐 아니라 요청에 실린 <b>모든</b> 이름을 보므로, 판정이 API별 파라미터 목록에 따라 갈리지 않는다.
     *
     * <p>호출 위치는 API마다 다르다. {@code @ModelAttribute}로 받는 API-028은 바인딩이 실패하기 전에 걸러야 해서
     * {@code @InitBinder}에서 부르지만, {@code @RequestParam}만 쓰는 API-034·035는 {@code @InitBinder}가 호출되지
     * 않으므로 핸들러 진입부에서 직접 부른다. 질의만 잘못된 요청의 응답은 어느 쪽이든 같다. 다만 진입부 호출은 인자 해석 뒤라, 본문까지 함께 잘못된 API-035
     * 요청은 Content-Type 오류(415)나 본문 오류가 먼저 나간다.
     */
    static void rejectIndexedOrNested(WebRequest request) {
        boolean rejected;
        try {
            rejected = hasIndexedOrNestedName(request);
        } catch (RequestRejectedException exception) {
            // 이름을 읽는 것만으로 StrictHttpFirewall이 제어문자 같은 금지 문자를 검사한다. 이 예외는 필터 체인이 아니라
            // 핸들러 본문에서 나오므로 그대로 두면 전역 처리기의 catch-all에 걸려 500이 된다. 잘못된 요청이지 서버 오류가
            // 아니므로 다른 이름 오류와 같은 400으로 맞춘다. 검사 시점은 구현에 달렸으니 순회 전체를 감싼다.
            rejected = true;
        }
        if (rejected) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private static boolean hasIndexedOrNestedName(WebRequest request) {
        for (Iterator<String> names = request.getParameterNames(); names.hasNext(); ) {
            String name = names.next();
            if (name.indexOf('[') >= 0 || name.indexOf(']') >= 0 || name.indexOf('.') >= 0) {
                return true;
            }
        }
        return false;
    }
}
