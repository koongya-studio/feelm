/*
 * 백엔드 OpenAPI 스냅샷(`openapi.json`)에서 만든 파일이다. **직접 고치지 않는다.**
 *
 * 다시 만들기: `npm run api:generate`
 * 스냅샷 갱신: 백엔드에서 `FEELM_OPENAPI_SNAPSHOT_WRITE=1 ./gradlew test --tests '*OpenApiSnapshotTest'`
 *
 * 서버에 없는 필드를 여기에 손으로 더하지 않는다 — 화면용 값은 뷰 모델에서 따로 만든다
 * (`frontend/docs/screens/frontend-structure.md` 8.3).
 */
export interface paths {
    "/api/v1/auth/email/verification-code": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["sendVerificationCode"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/auth/email/verification-code/verify": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["verifyVerificationCode"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/auth/login": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["login_1"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/auth/logout": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["logout"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/auth/oauth/{provider}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["login"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/auth/oauth/signup": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["signup_1"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/auth/password/reset": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["resetPassword"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/auth/password/reset-request": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["requestPasswordReset"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/auth/private-test": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["privateAuth"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/auth/signup": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["signup"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/auth/token/refresh": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["refresh"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/frames/{frameId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["getFrameDetail"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/movies/{movieId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * 영화 상세 조회
         * @description 영화 메타데이터, 출연진·감독, 전체 평점 집계와 OTT 제공처를 조회합니다. 선택 인증 API로 비회원도 조회할 수 있으며 myRating, myWatchStatus, recommendation은 null이고 OTT subscribed는 false입니다. 유효한 Access Token이 있으면 본인 평가·감상 상태·구독을 포함합니다. recommendationItemId는 조회 영화와 일치하고 본인 개인 추천 또는 현재 참여 중인 파티 추천일 때만 추천 이유를 반환하며, 권한이 없으면 recommendation=null과 200을 반환합니다. 유효하지 않은 토큰·Refresh Token·다른 인증 스킴은 비회원으로 처리합니다.
         */
        get: operations["getDetail"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/movies/{movieId}/dismiss": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["dismiss"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/movies/{movieId}/ott-clicks": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * OTT 링크 클릭 기록
         * @description 로그인한 활성 사용자의 보러가기 클릭을 기록합니다. 활성 감상 기록이 LINK_CLICKED이면 같은 행의 OTT와 시각을 갱신하고, EXPIRED 이력만 있으면 새 LINK_CLICKED 기록을 만듭니다. WATCHED_UNRATED·RATED 상태는 유지하며, 성공한 실제 호출마다 추천 행동 신호인 OTT_CLICK 아웃박스 이벤트를 한 건 저장합니다.
         */
        post: operations["click"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/movies/{movieId}/ratings": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * 평가 생성
         * @description 영화에 0.5점 단위 평가와 선택적 한줄평을 저장합니다. 한줄평의 스포일러 여부는 생략 시 false입니다. 평점, 감상 상태, 프레임과 추천 갱신 이벤트는 같은 트랜잭션으로 처리됩니다.
         */
        post: operations["create_2"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/movies/{movieId}/reviews": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * 공개 한줄평 목록 조회
         * @description 로그인한 ACTIVE 회원이 영화 한줄평을 최신순으로 조회합니다. 타인의 PUBLIC과 본인의 PUBLIC·PRIVATE 한줄평만 포함하며, 양방향 차단 상대와 비활성 작성자는 제외합니다.
         */
        get: operations["getReviews"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/movies/{movieId}/similar": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["getSimilarMovies"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/movies/filter-options": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * 검색 필터 옵션 조회
         * @description 영화 검색 필터에 사용할 장르·국가·개봉연도 범위와 지원 OTT 목록을 조회합니다.
         */
        get: operations["getOptions"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/movies/search": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * 영화 검색
         * @description 영화 제목·원제·배우·감독을 검색하고 장르·국가·개봉연도·OTT 조건으로 필터링합니다.
         */
        get: operations["search_1"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/movies/trending": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * 급상승 영화 조회
         * @description 최근 회원의 영화 상세 조회 추이를 Spark가 사전 집계한 급상승 목록을 조회합니다. 공개 API이며 요청 시 순위를 다시 계산하지 않습니다. 유효한 집계가 아직 없으면 확정된 초기 고정 목록(source=DEFAULT)을 반환하고, 첫 유효 집계 이후에는 최신 결과가 만료돼도 마지막 유효 결과를 stale=true로 계속 반환합니다.
         */
        get: operations["getTrending"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/notifications": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * 인앱 알림 목록
         * @description 현재 사용자에게 노출 중인 알림을 최신순으로 조회한다.
         */
        get: operations["list_2"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/notifications/{notificationId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post?: never;
        /**
         * 알림 숨김
         * @description 현재 사용자의 알림을 목록과 미확인 개수에서 제외한다.
         */
        delete: operations["dismiss_1"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/notifications/{notificationId}/read": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        /** 알림 읽음 처리 */
        patch: operations["markRead"];
        trace?: never;
    };
    "/api/v1/notifications/push/config": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** 브라우저 푸시 설정 조회 */
        get: operations["config"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/notifications/push/subscriptions": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        /** 브라우저 푸시 구독 등록 또는 갱신 */
        put: operations["register"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/notifications/push/subscriptions/{subscriptionId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post?: never;
        /** 브라우저 푸시 구독 해제 */
        delete: operations["unregister"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/notifications/read-all": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        /** 전체 알림 읽음 처리 */
        patch: operations["markAllRead"];
        trace?: never;
    };
    "/api/v1/notifications/unread-count": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** 미확인 알림 개수 */
        get: operations["unreadCount"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/onboarding/cards": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["getCards"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/onboarding/preferences": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["submit"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/otts": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["getOtts"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/parties": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["list"];
        put?: never;
        post: operations["create_1"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/parties/{partyId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post?: never;
        delete: operations["close"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/parties/{partyId}/invitations": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["invite"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/parties/{partyId}/leave": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["leave"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/parties/{partyId}/members/{userId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post?: never;
        delete: operations["kick"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/parties/{partyId}/popcorn": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["getPartyPopcorn"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/parties/{partyId}/recommendations": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["recommendations"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/parties/{partyId}/recommendations/history": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["recommendationHistory"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/parties/invitations": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["listInvitations"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/parties/invitations/{invitationId}/accept": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["acceptInvitation"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/parties/invitations/{invitationId}/decline": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["declineInvitation"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/popcorn-flavors": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["getPopcornFlavors"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/ratings/{ratingId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        /**
         * 평가 수정
         * @description 본인 평가의 별점, 한줄평, 공개 범위와 스포일러 여부를 전체 교체합니다. 기존 프레임은 유지하고 영화 평점 집계와 RATING UPDATE 이벤트를 같은 트랜잭션에 기록합니다.
         */
        put: operations["update"];
        post?: never;
        /**
         * 평가 삭제
         * @description 본인 평가와 연결 프레임을 삭제하고 감상 기록은 보존합니다. 영화 평점 집계와 RATING DELETE 이벤트를 같은 트랜잭션에 기록합니다.
         */
        delete: operations["delete_1"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/recommendations": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["getRecommendations"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/recommendations/history": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["getHistory"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/recommendations/otts": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["getOttRecommendations"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/report-images/{imageId}/download": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["download"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/reports/{reportId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["detail"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/reports/{reportId}/image": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["create"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/reports/{reportId}/issue": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["issue"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/users/{publicId}/profile": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * 공개 프로필 조회
         * @description PUBLIC 사용자의 닉네임·취향 키워드·팝콘·최근 필름·공개 한줄평 수를 조회합니다. 비회원도 호출할 수 있습니다. 없는 사용자, PRIVATE 사용자, 로그인 사용자와 차단 관계인 사용자는 모두 404로 구분 없이 응답합니다. 본인이 자신의 publicId로 조회하면 공개 범위와 무관하게 200입니다.
         */
        get: operations["getPublicProfile"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/users/{userId}/block": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["block"];
        delete: operations["unblock"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/users/me": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["getProfile"];
        put?: never;
        post?: never;
        delete: operations["withdraw"];
        options?: never;
        head?: never;
        patch: operations["updateProfile"];
        trace?: never;
    };
    "/api/v1/users/me/avatar": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put: operations["upload"];
        post?: never;
        delete: operations["delete"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/users/me/blocks": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["blocks"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/users/me/frames": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["getMyFrames"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/users/me/notification-settings": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["getMySettings"];
        put: operations["replaceMySettings"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/users/me/oauth/{provider}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["link"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/users/me/otts": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["getMyOtts"];
        put: operations["replaceMyOtts"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/users/me/password": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put: operations["changePassword"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/users/me/popcorn": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["getMyPopcornBucket"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/users/me/ratings": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * 내 평가·미평가 영화 목록
         * @description ACTIVE 회원 본인의 평가를 조회합니다. 필터 생략 시 기존 평가-only 최신순이며, ALL은 평가와 감상 후 미평가 영화를 한 페이지로 합칩니다. 미평가 영화는 별점순에서 항상 뒤에 옵니다.
         */
        get: operations["getMyRatings"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/users/me/reports": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["list_1"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/users/me/taste": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["getMyTasteAnalysis"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/users/nickname-availability": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["check"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/users/search": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["search"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/watch-records": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * 감상 기록 목록
         * @description 로그인한 활성 사용자의 감상 기록을 상태별로 조회합니다. 7일 만료 경계에 도달한 LINK_CLICKED 기록은 같은 트랜잭션에서 EXPIRED로 반영하며, 커서는 사용자와 필터 조건에 묶입니다.
         */
        get: operations["getWatchRecords"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/watch-records/{watchRecordId}/confirm": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * 감상 확인 응답
         * @description 본인의 만료 전 LINK_CLICKED 기록에 응답합니다. 클릭 직후부터 응답할 수 있으며, 봤어요는 WATCHED_UNRATED, 안 봤어요는 EXPIRED로 전이합니다. 평가 삭제 후 WATCHED_UNRATED 기록은 클릭 기한이 지나도 안 봤어요로 취소할 수 있습니다.
         */
        post: operations["confirm"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
}
export type webhooks = Record<string, never>;
export interface components {
    schemas: {
        AccountWithdrawalRequest: {
            code?: string;
            password?: string;
            provider?: string;
            redirectUri?: string;
        };
        Analysis: {
            countries?: components["schemas"]["Country"][];
            directors?: components["schemas"]["Director"][];
            eras?: components["schemas"]["Era"][];
            genres?: components["schemas"]["Genre"][];
        };
        AvatarResponse: {
            avatarUrl?: string;
        };
        AvatarUserSummary: {
            avatarUrl?: string;
            nickname?: string;
            /** Format: int64 */
            userId?: number;
        };
        AxisJudgement: {
            axis?: string;
            label?: string;
            value?: string;
        };
        BlockedUserResponse: {
            /** Format: date-time */
            blockedAt?: string;
            nickname?: string;
            /** Format: int64 */
            userId?: number;
        };
        /** @description TMDb display_order 기준 상위 10명의 배우 크레딧 */
        CastResponse: {
            /**
             * @description 배역명. 없으면 null
             * @example Tyler Durden
             */
            character: string | null;
            /**
             * @description 배우 이름
             * @example 브래드 피트
             */
            name: string;
            /**
             * Format: int64
             * @description 서비스 인물 ID
             * @example 287
             */
            personId: number;
            /** @description 작은 인물 프로필 이미지 URL. 없으면 null */
            profileUrl: string | null;
        };
        CharacterBasis: {
            /** Format: int32 */
            frameCount?: number;
            lowSample?: boolean;
        };
        CharacterCardContent: {
            axes?: components["schemas"]["AxisJudgement"][];
            basis?: components["schemas"]["CharacterBasis"];
            code?: string;
            description?: string;
            name?: string;
            tagline?: string;
        };
        CharacterCutCard: {
            displayName?: string;
            name?: string;
        };
        Comparison: {
            /** Format: int32 */
            delta?: number;
            isNew?: boolean;
            label?: string;
        };
        Country: {
            averageScore?: number;
            code?: string;
            /** Format: int32 */
            count?: number;
            name?: string;
            share?: number;
        };
        /** @description 제작 국가. 수집 원문의 역사적 국가·지역 코드를 유지합니다. */
        CountryResponse: {
            /**
             * @description 국가 코드
             * @example KR
             */
            code: string;
            /**
             * @description 한국어 국가 표시명
             * @example 한국
             */
            name: string;
        };
        Current: {
            /** Format: date-time */
            issuedAt?: string;
            period?: components["schemas"]["ReportPeriod"];
            /** Format: int64 */
            reportId?: number;
            status?: string;
        };
        CursorPageResponseBlockedUserResponse: {
            hasNext?: boolean;
            items?: components["schemas"]["BlockedUserResponse"][];
            nextCursor?: string;
        };
        CursorPageResponsePartyListItemResponse: {
            hasNext?: boolean;
            items?: components["schemas"]["PartyListItemResponse"][];
            nextCursor?: string;
        };
        CursorPageResponsePartyRecommendationHistorySetResponse: {
            hasNext?: boolean;
            items?: components["schemas"]["PartyRecommendationHistorySetResponse"][];
            nextCursor?: string;
        };
        CursorPageResponseReceivedPartyInvitationResponse: {
            hasNext?: boolean;
            items?: components["schemas"]["ReceivedPartyInvitationResponse"][];
            nextCursor?: string;
        };
        CursorPageResponseRecommendationHistorySetResponse: {
            hasNext?: boolean;
            items?: components["schemas"]["RecommendationHistorySetResponse"][];
            nextCursor?: string;
        };
        CursorPageResponseSearchedUserResponse: {
            hasNext?: boolean;
            items?: components["schemas"]["SearchedUserResponse"][];
            nextCursor?: string;
        };
        Director: {
            averageScore?: number;
            /** Format: int32 */
            count?: number;
            name?: string;
            /** Format: int64 */
            personId?: number;
        };
        DirectorCard: {
            displayName?: string;
            image?: components["schemas"]["ImageRef"];
            name?: string;
            work?: components["schemas"]["ImageRef"];
        };
        /** @description 감독 크레딧. 인원 제한 없이 반환합니다. */
        DirectorResponse: {
            /**
             * @description 감독 이름
             * @example 데이비드 핀처
             */
            name: string;
            /**
             * Format: int64
             * @description 서비스 인물 ID
             * @example 7467
             */
            personId: number;
            /** @description 작은 인물 프로필 이미지 URL. 없으면 null */
            profileUrl: string | null;
        };
        EmailVerificationCodeRequest: {
            /** Format: email */
            email: string;
        };
        EmailVerificationCodeVerifyRequest: {
            code: string;
            /** Format: email */
            email: string;
        };
        EmailVerificationTokenResponse: {
            emailVerificationToken?: string;
            /** Format: int64 */
            expiresIn?: number;
        };
        Era: {
            averageScore?: number;
            /** Format: int32 */
            count?: number;
            label?: string;
            share?: number;
        };
        ErrorResponse: {
            code: string;
            details?: unknown;
            message: string;
        };
        FlavorBucket: {
            averageScore?: number;
            flavorCode?: string;
            /** Format: int64 */
            popcornCount?: number;
        };
        FlavorChange: {
            /** Format: int32 */
            after?: number;
            /** Format: int32 */
            before?: number;
            flavorCode?: string;
        };
        FourCut: {
            actor?: components["schemas"]["PersonCard"];
            character?: components["schemas"]["CharacterCutCard"];
            director?: components["schemas"]["DirectorCard"];
            genre?: components["schemas"]["GenreCard"];
        };
        Frame: {
            /** Format: date-time */
            createdAt?: string;
            /** Format: int64 */
            frameId?: number;
        };
        FrameDetailResponse: {
            flavorCode?: string;
            /** Format: int64 */
            frameId?: number;
            movie?: components["schemas"]["Movie"];
            rating?: components["schemas"]["Rating"];
            watchRecord?: components["schemas"]["WatchRecord"];
        };
        FrameListResponse: {
            hasNext?: boolean;
            items?: components["schemas"]["Item"][];
            nextCursor?: string;
            /** Format: int64 */
            totalCount?: number;
        };
        Genre: {
            averageScore?: number;
            code?: string;
            /** Format: int32 */
            count?: number;
            name?: string;
            share?: number;
        };
        GenreCard: {
            displayName?: string;
            image?: components["schemas"]["ImageRef"];
            name?: string;
        };
        GenreRank: {
            code?: string;
            /** Format: int32 */
            count?: number;
            name?: string;
        };
        /** @description 영화에 연결된 장르 */
        GenreResponse: {
            /**
             * @description 장르 코드
             * @example DRAMA
             */
            code: string;
            /**
             * @description 장르 표시명
             * @example 드라마
             */
            name: string;
        };
        ImagePosition: {
            /** Format: int32 */
            x?: number;
            /** Format: int32 */
            y?: number;
        };
        ImageRef: {
            cutoutUrl?: string;
            position?: components["schemas"]["ImagePosition"];
            url?: string;
            zoom?: number;
        };
        InviteeSummary: {
            nickname?: string;
            /** Format: int64 */
            userId?: number;
        };
        Item: {
            /** Format: int64 */
            movieId: number;
            /** @enum {string} */
            preference: "LIKE" | "DISLIKE";
        };
        JsonNode: {
            array?: boolean;
            bigDecimal?: boolean;
            bigInteger?: boolean;
            binary?: boolean;
            boolean?: boolean;
            container?: boolean;
            double?: boolean;
            embeddedValue?: boolean;
            empty?: boolean;
            float?: boolean;
            floatingPointNumber?: boolean;
            int?: boolean;
            integralNumber?: boolean;
            long?: boolean;
            missingNode?: boolean;
            /** @enum {string} */
            nodeType?: "ARRAY" | "BINARY" | "BOOLEAN" | "MISSING" | "NULL" | "NUMBER" | "OBJECT" | "POJO" | "STRING";
            null?: boolean;
            number?: boolean;
            object?: boolean;
            pojo?: boolean;
            short?: boolean;
            string?: boolean;
            /** @deprecated */
            textual?: boolean;
            valueNode?: boolean;
        };
        Keys: {
            auth: string;
            p256dh: string;
        };
        Keyword: {
            code?: string;
            label?: string;
        };
        LoginRequest: {
            email: string;
            password: string;
        };
        LoginResponse: {
            accessToken?: string;
            /** Format: int64 */
            expiresIn?: number;
            user?: components["schemas"]["UserSummary"];
        };
        Member: {
            avatarUrl?: string;
            /** Format: date-time */
            joinedAt?: string;
            nickname?: string;
            /** @enum {string} */
            role?: "OWNER" | "MEMBER";
            /** Format: int64 */
            userId?: number;
        };
        MemberPopcorn: {
            flavors?: components["schemas"]["FlavorBucket"][];
            /** Format: int64 */
            totalCount?: number;
            user?: components["schemas"]["UserSummary"];
        };
        /** @description 평가 피드의 영화 요약 */
        Movie: {
            /**
             * Format: int64
             * @description 서비스 영화 ID
             */
            movieId: number;
            /** @description 포스터 URL. 없으면 null */
            posterUrl: string | null;
            /** @description 영화 제목 */
            title: string;
        };
        /** @description 영화 상세. 값이 없는 선택 필드는 null, 연결 데이터가 없는 컬렉션은 빈 배열로 반환합니다. */
        MovieDetailResponse: {
            /**
             * @description 전체 사용자 평균 평점. 차단 필터 미적용, 평가 없으면 0
             * @example 4.3
             */
            averageRating: number;
            /** @description 배경 이미지 URL. 없으면 null */
            backdropUrl: string | null;
            /** @description 배우 최대 10명. display_order 오름차순(null은 마지막), 동률은 personId 순 */
            cast: components["schemas"]["CastResponse"][];
            /** @description 제작 국가 코드와 한국어 표시명. 없으면 빈 배열 */
            countries: components["schemas"]["CountryResponse"][];
            /** @description 감독 전체. display_order 오름차순(null은 마지막), 동률은 personId 순 */
            directors: components["schemas"]["DirectorResponse"][];
            /** @description 대표 팝콘 맛 코드. 미배정이면 null */
            flavorCode: string | null;
            /** @description 영화에 연결된 장르. 없으면 빈 배열 */
            genres: components["schemas"]["GenreResponse"][];
            /**
             * Format: int64
             * @description 서비스 영화 ID
             * @example 550
             */
            movieId: number;
            /** @description 본인 평가. 비회원 또는 평가 없으면 null */
            myRating: components["schemas"]["MyRatingResponse"] | null;
            /** @description 현재 감상 상태(LINK_CLICKED, WATCHED_UNRATED, RATED). 비회원·기록 없음·EXPIRED만 있으면 null */
            myWatchStatus: string | null;
            /**
             * @description 원제. 없으면 null
             * @example Fight Club
             */
            originalTitle: string | null;
            /** @description 영화에 연결된 OTT. 구독 우선, 그룹 내 ottId 오름차순. 없으면 빈 배열 */
            ottProviders: components["schemas"]["MovieOttProviderResponse"][];
            /** @description 포스터 URL. 없으면 null */
            posterUrl: string | null;
            /**
             * Format: int32
             * @description 전체 사용자 평가 수. 차단 필터 미적용
             * @example 1280
             */
            ratingCount: number;
            /** @description 영화 일치 및 열람 권한이 확인된 추천 이유. 비회원·ID 미제공·권한 없음이면 null */
            recommendation: components["schemas"]["MovieRecommendationResponse"] | null;
            /**
             * Format: date
             * @description 개봉일. 없으면 null
             * @example 1999-10-15
             */
            releaseDate: string | null;
            /**
             * Format: int32
             * @description 상영 시간(분). 없으면 null
             * @example 139
             */
            runtimeMinutes: number | null;
            /** @description 줄거리. 없으면 null */
            synopsis: string | null;
            /**
             * @description 영화 제목
             * @example 파이트 클럽
             */
            title: string;
        };
        /** @description 영화 검색 필터 옵션 */
        MovieFilterOptionsResponse: {
            /** @description 영화에 저장된 제작 국가 목록 */
            countries: components["schemas"]["Option"][];
            /** @description 영화와 연결된 장르 목록 */
            genres: components["schemas"]["Option"][];
            /** @description 영화와 연결된 지원 OTT 목록 */
            otts: components["schemas"]["OttOption"][];
            /** @description 개봉연도 최소·최대 범위 */
            yearRange: components["schemas"]["YearRange"];
        };
        /** @description 영화의 OTT 제공처와 현재 사용자의 구독 여부 */
        MovieOttProviderResponse: {
            /**
             * @description OTT 코드
             * @example NETFLIX
             */
            code: string;
            /**
             * @description OTT 표시명
             * @example 넷플릭스
             */
            name: string;
            /**
             * Format: int64
             * @description 서비스 OTT ID
             * @example 1
             */
            ottId: number;
            /**
             * @description 로그인 사용자의 구독 여부. 비회원은 false
             * @example true
             */
            subscribed: boolean;
            /** @description 시청 바로가기 URL. 없으면 null */
            watchUrl: string | null;
        };
        /** @description 본인 개인 추천 또는 현재 party_members에 속한 파티 추천의 저장된 이유 */
        MovieRecommendationResponse: {
            /**
             * @description 저장된 추천 이유 JSON 스냅샷. 재계산 없이 반환
             * @example {
             *       "factors": [
             *         {
             *           "label": "스릴러",
             *           "type": "GENRE"
             *         }
             *       ],
             *       "summary": "스릴러 장르를 높게 평가하셨어요"
             *     }
             */
            reason: {
                [key: string]: unknown;
            };
            /**
             * Format: int64
             * @description 추천 항목 ID
             * @example 3301
             */
            recommendationItemId: number;
            /**
             * @description 추천 유형
             * @enum {string}
             */
            type: "TASTE" | "DISCOVERY" | "PARTY";
        };
        MovieSearchItemResponse: {
            averageRating?: number;
            genres?: string[];
            /** Format: int64 */
            movieId?: number;
            originalTitle?: string;
            ottProviders?: string[];
            posterUrl?: string;
            /** Format: int32 */
            releaseYear?: number;
            title?: string;
            watched?: boolean;
        };
        /** @description 영화 검색 결과 */
        MovieSearchPageResponse: {
            /** @description 다음 페이지 존재 여부 */
            hasNext: boolean;
            /** @description 현재 페이지의 영화 목록 */
            items: components["schemas"]["MovieSearchItemResponse"][];
            /** @description 다음 페이지 조회용 불투명 커서 */
            nextCursor?: string | null;
            /**
             * Format: int64
             * @description 현재 검색조건에 일치하는 전체 영화 수
             * @example 24
             */
            totalCount: number;
        };
        /** @description 감상 기록의 영화 요약 */
        MovieSummary: {
            /**
             * Format: int64
             * @description 서비스 영화 ID
             * @example 550
             */
            movieId: number;
            /** @description 포스터 URL */
            posterUrl: string | null;
            /**
             * @description 영화 제목
             * @example 파이트 클럽
             */
            title: string;
        };
        /** @description 본인 평가 또는 감상 후 미평가 영화 */
        MyRatingItemResponse: {
            /**
             * Format: date-time
             * @description 목록 정렬 시각(UTC). 이력이 없으면 null
             */
            activityAt: string | null;
            /** @description 본인 한줄평 원문. 없으면 null */
            comment: string | null;
            /**
             * Format: date-time
             * @description 평가 생성 시각(UTC). 미평가이면 null
             */
            createdAt: string | null;
            /**
             * Format: int64
             * @description 프레임 ID. 없으면 null
             */
            frameId: number | null;
            /**
             * @description 평가 여부
             * @enum {string}
             */
            itemType: "RATED" | "UNRATED";
            /** @description 영화 요약 */
            movie: components["schemas"]["Movie"];
            /**
             * Format: int64
             * @description 평가 ID. 미평가이면 null
             */
            ratingId: number | null;
            /** @description 본인 별점. 미평가이면 null */
            score: number | null;
            /** @description 스포일러 여부. 미평가이면 null */
            spoiler: boolean | null;
            /**
             * @description 한줄평 공개 범위. 없으면 null
             * @enum {string|null}
             */
            visibility: "PUBLIC" | "PRIVATE" | null;
        };
        /** @description 본인 평가·미평가 통합 cursor 페이지 */
        MyRatingPageResponse: {
            /** @description 다음 페이지 존재 여부 */
            hasNext: boolean;
            /** @description 현재 페이지 */
            items: components["schemas"]["MyRatingItemResponse"][];
            /** @description 다음 페이지 cursor. 마지막 페이지면 null */
            nextCursor: string | null;
        };
        /** @description 현재 로그인 사용자의 평가. 비공개 한줄평도 본인에게만 반환합니다. */
        MyRatingResponse: {
            /** @description 내 한줄평. 없으면 null */
            comment: string | null;
            /**
             * Format: int64
             * @description 평가 ID
             * @example 91
             */
            ratingId: number;
            /**
             * @description 평점. 0.5~5.0, 0.5점 단위
             * @example 4.5
             */
            score: number;
            /**
             * @description 내 한줄평의 스포일러 포함 여부
             * @example true
             */
            spoiler: boolean;
            /** @description 한줄평 공개 범위(PUBLIC, PRIVATE). 한줄평이 없으면 null 가능 */
            visibility: string | null;
        };
        NicknameAvailabilityResponse: {
            available?: boolean;
            nickname?: string;
            reason?: string;
        };
        /** @description 인앱 알림 항목 */
        NotificationItemResponse: {
            /** @example 새로운 파티 초대가 도착했어요. */
            body?: string;
            /**
             * Format: int64
             * @example 101
             */
            notificationId?: number;
            /** Format: date-time */
            notifiedAt?: string;
            /** Format: date-time */
            readAt?: string | null;
            /**
             * Format: int64
             * @example 501
             */
            targetId?: number;
            /**
             * @example PARTY_INVITATION
             * @enum {string}
             */
            targetType?: "PARTY_INVITATION" | "PARTY" | "WATCH_RECORD";
            /** @example 파티 모드 초대 */
            title?: string;
            /**
             * @example PARTY_INVITE
             * @enum {string}
             */
            type?: "PARTY_INVITE" | "PARTY_INVITE_ACCEPTED" | "WATCH_CONFIRM";
        };
        /** @description 인앱 알림 커서 페이지 */
        NotificationPageResponse: {
            hasNext?: boolean;
            items?: components["schemas"]["NotificationItemResponse"][];
            nextCursor?: string | null;
        };
        NotificationSettingsResponse: {
            MARKETING?: boolean;
            RATING?: boolean;
            RECOMMENDATION?: boolean;
        };
        NotificationSettingsUpdateRequest: {
            MARKETING: boolean;
            RATING: boolean;
            RECOMMENDATION: boolean;
        };
        /** @description 미확인 인앱 알림 개수 */
        NotificationUnreadCountResponse: {
            /**
             * Format: int64
             * @example 2
             */
            count?: number;
        };
        OAuthCodeRequest: {
            code: string;
            redirectUri: string;
        };
        OAuthLinkResponse: {
            /** Format: date-time */
            linkedAt?: string;
            /** @enum {string} */
            provider?: "GOOGLE" | "KAKAO" | "NAVER";
        };
        OAuthSignupRequest: {
            /** Format: date */
            birthDate: string;
            /** @enum {string} */
            gender: "MALE" | "FEMALE" | "OTHER";
            nickname: string;
            signupToken: string;
        };
        OnboardingCardCriteria: {
            /** Format: int32 */
            size?: number;
        };
        OnboardingCardListResponse: {
            items?: components["schemas"]["Item"][];
        };
        OnboardingSubmitRequest: {
            movieIdUnique?: boolean;
            preferences: components["schemas"]["Item"][];
            skipConsistent?: boolean;
            skipped: boolean;
        };
        OnboardingSubmitResponse: {
            onboardingCompleted?: boolean;
            /** Format: int32 */
            savedCount?: number;
            skipped?: boolean;
        };
        /** @description 코드와 한국어 표시명 */
        Option: {
            /** @example THRILLER */
            code: string;
            /** @example 스릴러 */
            name: string;
        };
        Ott: {
            code?: string;
            logoUrl?: string;
            name?: string;
            /** Format: int64 */
            ottId?: number;
        };
        /** @description 영화 OTT 링크 클릭 요청 */
        OttClickRequest: {
            /**
             * Format: int64
             * @description 영화에 연결된 OTT의 서비스 ID. 양의 64비트 정수
             * @example 1
             */
            ottId: number;
        };
        /** @description OTT 클릭으로 처리된 감상 기록 */
        OttClickResponse: {
            /**
             * Format: date-time
             * @description 최근 OTT 클릭 시각(UTC). 클릭 이력 없는 평가 기록이면 null
             * @example 2026-08-30T05:21:00Z
             */
            clickedAt: string | null;
            /**
             * Format: date-time
             * @description 감상 확인 가능 시각(UTC, clickedAt과 같다). 클릭 이력이 없으면 null
             * @example 2026-08-30T05:21:00Z
             */
            confirmDueAt: string | null;
            /**
             * Format: date-time
             * @description 감상 기록 만료 시각(UTC, clickedAt + 7일). 클릭 이력이 없으면 null
             * @example 2026-09-06T05:21:00Z
             */
            expiresAt: string | null;
            /**
             * Format: int64
             * @description 서비스 영화 ID
             * @example 550
             */
            movieId: number;
            /**
             * @description 현재 감상 상태
             * @enum {string}
             */
            status: "LINK_CLICKED" | "WATCHED_UNRATED" | "RATED";
            /**
             * Format: int64
             * @description 처리된 감상 기록 ID
             * @example 5001
             */
            watchRecordId: number;
        };
        OttListResponse: {
            items?: components["schemas"]["Item"][];
        };
        /** @description 지원 OTT */
        OttOption: {
            /** @example NETFLIX */
            code: string;
            /** @example 넷플릭스 */
            name: string;
            /**
             * Format: int64
             * @example 1
             */
            ottId: number;
        };
        OttRecommendationResponse: {
            /** Format: date-time */
            aggregatedAt?: string;
            insufficientData?: boolean;
            items?: components["schemas"]["Item"][];
        };
        /** @description 클릭한 OTT 요약 */
        OttSummary: {
            /**
             * @description OTT 코드
             * @example NETFLIX
             */
            code: string;
            /**
             * Format: int64
             * @description 서비스 OTT ID
             * @example 1
             */
            ottId: number;
        };
        PartyCreateRequest: {
            /** Format: int32 */
            maxMembers?: number;
            name: string;
        };
        PartyInvitationCreateRequest: {
            /** Format: int64 */
            userId: number;
        };
        PartyInvitationResponse: {
            /** Format: date-time */
            createdAt?: string;
            /** Format: int64 */
            invitationId?: number;
            invitee?: components["schemas"]["UserSummary"];
            /** Format: int64 */
            partyId?: number;
            /** @enum {string} */
            status?: "PENDING" | "ACCEPTED" | "DECLINED";
        };
        PartyInvitationStatusResponse: {
            /** Format: int64 */
            invitationId?: number;
            /** @enum {string} */
            status?: "PENDING" | "ACCEPTED" | "DECLINED";
        };
        PartyListItemResponse: {
            /** Format: date-time */
            createdAt?: string;
            /** Format: int32 */
            maxMembers?: number;
            members?: components["schemas"]["Member"][];
            name?: string;
            owner?: components["schemas"]["AvatarUserSummary"];
            /** Format: int64 */
            partyId?: number;
            pendingInvitations?: components["schemas"]["PendingInvitation"][];
            /** @enum {string} */
            status?: "ACTIVE" | "CLOSED";
        };
        PartyPopcornResponse: {
            members?: components["schemas"]["MemberPopcorn"][];
            /** Format: int64 */
            partyId?: number;
        };
        PartyRecommendationHistoryItemResponse: {
            /** Format: date-time */
            exposedAt?: string;
            movie?: components["schemas"]["RecommendationMovieResponse"];
            /** Format: int64 */
            recommendationItemId?: number;
            type?: string;
        };
        PartyRecommendationHistorySetResponse: {
            /** Format: date-time */
            generatedAt?: string;
            items?: components["schemas"]["PartyRecommendationHistoryItemResponse"][];
            /** Format: int64 */
            recommendationSetId?: number;
        };
        PartyRecommendationItemResponse: {
            movie?: components["schemas"]["RecommendationMovieResponse"];
            /** Format: int64 */
            recommendationItemId?: number;
            type?: string;
        };
        PartyRecommendationResponse: {
            /** Format: date-time */
            generatedAt?: string;
            hasNext?: boolean;
            items?: components["schemas"]["PartyRecommendationItemResponse"][];
            /** Format: int64 */
            partyId?: number;
            /** Format: int64 */
            recommendationSetId?: number;
        };
        PartyResponse: {
            /** Format: date-time */
            createdAt?: string;
            /** Format: int32 */
            maxMembers?: number;
            members?: components["schemas"]["Member"][];
            name?: string;
            owner?: components["schemas"]["AvatarUserSummary"];
            /** Format: int64 */
            partyId?: number;
            /** @enum {string} */
            status?: "ACTIVE" | "CLOSED";
        };
        PartyStateResponse: {
            /** Format: date-time */
            closedAt?: string;
            /** @enum {string} */
            closedReason?: "INSUFFICIENT_MEMBERS" | "OWNER_CLOSED";
            /** Format: int64 */
            ownerId?: number;
            /** Format: int64 */
            partyId?: number;
            /** @enum {string} */
            status?: "ACTIVE" | "CLOSED";
        };
        PartySummary: {
            /** Format: int64 */
            memberCount?: number;
            name?: string;
            owner?: components["schemas"]["UserSummary"];
            /** Format: int64 */
            partyId?: number;
        };
        PasswordChangeRequest: {
            currentPassword: string;
            newPassword: string;
        };
        PasswordResetMailRequest: {
            /** Format: email */
            email: string;
        };
        PasswordResetRequest: {
            newPassword: string;
            token: string;
        };
        PendingInvitation: {
            /** Format: date-time */
            createdAt?: string;
            /** Format: int64 */
            invitationId?: number;
            invitee?: components["schemas"]["InviteeSummary"];
        };
        PersonCard: {
            displayName?: string;
            image?: components["schemas"]["ImageRef"];
            name?: string;
        };
        PersonRank: {
            /** Format: int32 */
            count?: number;
            name?: string;
            /** Format: int64 */
            personId?: number;
        };
        Popcorn: {
            flavors?: components["schemas"]["FlavorBucket"][];
        };
        PopcornBucketResponse: {
            flavors?: components["schemas"]["FlavorBucket"][];
            /** Format: int64 */
            totalCount?: number;
        };
        PopcornFlavorListResponse: {
            items?: components["schemas"]["Item"][];
        };
        Profile: {
            avatarUrl?: string;
            nickname?: string;
            /** Format: int64 */
            userId?: number;
            /** @enum {string} */
            visibility?: "PUBLIC" | "PRIVATE";
        };
        ProfileUpdateRequest: {
            /** Format: date */
            birthDate?: string;
            /** @enum {string} */
            gender?: "MALE" | "FEMALE" | "OTHER";
            nickname?: string;
            /** @enum {string} */
            visibility?: "PUBLIC" | "PRIVATE";
        };
        PublicProfileResponse: {
            isBlockedByMe?: boolean;
            keywords?: components["schemas"]["Keyword"][];
            popcorn?: components["schemas"]["Popcorn"];
            /** Format: int64 */
            publicReviewCount?: number;
            recentFrames?: components["schemas"]["RecentFrame"][];
            stats?: components["schemas"]["Stats"];
            user?: components["schemas"]["Profile"];
        };
        /** @description 공개 한줄평 목록 항목 */
        PublicReviewItemResponse: {
            /**
             * @description 한줄평 원문
             * @example 스포일러가 포함된 한줄평
             */
            comment: string;
            /**
             * Format: date-time
             * @description 평가 생성 시각(UTC)
             * @example 2026-09-16T01:00:00Z
             */
            createdAt: string;
            /**
             * @description 로그인 사용자가 작성한 평가인지 여부
             * @example false
             */
            mine: boolean;
            /**
             * Format: int64
             * @description 평가 ID
             * @example 91
             */
            ratingId: number;
            /**
             * @description 평점(0.5~5.0, 0.5 단위)
             * @example 4.5
             */
            score: number;
            /**
             * @description 작성자가 한줄평을 스포일러로 표시했는지 여부
             * @example true
             */
            spoiler: boolean;
            /** @description 작성자 요약 */
            user: components["schemas"]["PublicReviewUserResponse"];
        };
        /** @description 공개 한줄평 cursor 페이지 */
        PublicReviewPageResponse: {
            /** @description 다음 페이지 존재 여부 */
            hasNext: boolean;
            /** @description 현재 페이지의 한줄평 */
            items: components["schemas"]["PublicReviewItemResponse"][];
            /** @description 다음 페이지 cursor. 마지막 페이지면 null */
            nextCursor: string | null;
        };
        /** @description 공개 한줄평 작성자 요약 */
        PublicReviewUserResponse: {
            /**
             * @description 작성자 닉네임
             * @example 팝콘러버
             */
            nickname: string;
            /**
             * Format: int64
             * @description 작성자 사용자 ID
             * @example 1024
             */
            userId: number;
        };
        Rating: {
            comment?: string;
            /** Format: int64 */
            ratingId?: number;
            score?: number;
            visibility?: string;
        };
        RatingCreateRequest: {
            comment?: string;
            score: number;
            /**
             * @description 한줄평에 스포일러가 포함됐는지 여부. 생략 시 false
             * @default false
             */
            spoiler: boolean;
            /** @enum {string} */
            visibility?: "PUBLIC" | "PRIVATE";
        };
        RatingCreateResponse: {
            comment?: string;
            /** Format: date-time */
            createdAt?: string;
            frame?: components["schemas"]["Frame"];
            /** Format: int64 */
            movieId?: number;
            popcorn?: components["schemas"]["Popcorn"];
            /** Format: int64 */
            ratingId?: number;
            score?: number;
            /** @description 작성자가 선택한 한줄평 스포일러 여부 */
            spoiler: boolean;
            /** @enum {string} */
            visibility?: "PUBLIC" | "PRIVATE";
            /** @enum {string} */
            watchStatus?: "LINK_CLICKED" | "WATCHED_UNRATED" | "RATED" | "EXPIRED";
        };
        RatingUpdateRequest: {
            comment?: string;
            score: number;
            /**
             * @description 한줄평 스포일러 여부. 생략 시 false
             * @default false
             */
            spoiler: boolean;
            /** @enum {string} */
            visibility?: "PUBLIC" | "PRIVATE";
        };
        ReceivedPartyInvitationResponse: {
            /** Format: date-time */
            createdAt?: string;
            /** Format: int64 */
            invitationId?: number;
            party?: components["schemas"]["PartySummary"];
            /** @enum {string} */
            status?: "PENDING" | "ACCEPTED" | "DECLINED";
        };
        RecentFrame: {
            /** Format: date-time */
            createdAt?: string;
            /** Format: int64 */
            frameId?: number;
            movie?: components["schemas"]["Movie"];
            score?: number;
        };
        RecommendationDismissRequest: {
            /** Format: int64 */
            recommendationItemId?: number;
        };
        RecommendationDismissResponse: {
            /** Format: int64 */
            movieId?: number;
        };
        RecommendationHistoryItemResponse: {
            dismissed?: boolean;
            movie?: components["schemas"]["RecommendationHistoryMovieResponse"];
            reason?: components["schemas"]["JsonNode"];
            /** Format: int64 */
            recommendationItemId?: number;
            type?: string;
            watchStatus?: string;
        };
        RecommendationHistoryMovieResponse: {
            /** Format: int64 */
            movieId?: number;
            posterUrl?: string;
            title?: string;
        };
        RecommendationHistorySetResponse: {
            /** Format: date-time */
            generatedAt?: string;
            items?: components["schemas"]["RecommendationHistoryItemResponse"][];
            /** Format: int64 */
            recommendationSetId?: number;
        };
        RecommendationItemResponse: {
            flavorCode?: string;
            movie?: components["schemas"]["RecommendationMovieResponse"];
            reason?: components["schemas"]["JsonNode"];
            /** Format: int64 */
            recommendationItemId?: number;
            type?: string;
        };
        RecommendationMovieResponse: {
            averageRating?: number;
            genres?: string[];
            /** Format: int64 */
            movieId?: number;
            ottProviders?: string[];
            posterUrl?: string;
            /** Format: int32 */
            releaseYear?: number;
            title?: string;
        };
        RecommendationResponse: {
            /** Format: date-time */
            generatedAt?: string;
            items?: components["schemas"]["RecommendationItemResponse"][];
            /** Format: int64 */
            recommendationSetId?: number;
        };
        ReportDetailResponse: {
            characterCard?: components["schemas"]["CharacterCardContent"];
            comparisons?: components["schemas"]["Comparison"][];
            countryShares?: components["schemas"]["Share"][];
            /** Format: date-time */
            createdAt?: string;
            eraShares?: components["schemas"]["Share"][];
            flavorChanges?: components["schemas"]["FlavorChange"][];
            fourCut?: components["schemas"]["FourCut"];
            genreShares?: components["schemas"]["Share"][];
            highlights?: string[];
            keywords?: components["schemas"]["Keyword"][];
            period?: components["schemas"]["ReportPeriod"];
            /** Format: int64 */
            reportId?: number;
            summary?: components["schemas"]["Summary"];
            topActors?: components["schemas"]["PersonRank"][];
            topDirectors?: components["schemas"]["PersonRank"][];
            topGenres?: components["schemas"]["GenreRank"][];
        };
        ReportImageResponse: {
            downloadUrl?: string;
            /** Format: date-time */
            expiresAt?: string;
            format?: string;
            /** Format: int64 */
            imageId?: number;
        };
        ReportIssueResponse: {
            /** Format: date-time */
            issuedAt?: string;
            /** Format: int64 */
            reportId?: number;
        };
        ReportListResponse: {
            current?: components["schemas"]["Current"];
            items?: components["schemas"]["Item"][];
            preparing?: boolean;
        };
        ReportPeriod: {
            /** @enum {string} */
            half?: "H1" | "H2";
            /** Format: int32 */
            year?: number;
        };
        SampleRequest: {
            nickname: string;
            /** Format: int32 */
            score?: number;
        };
        SearchedUserResponse: {
            nickname?: string;
            /** Format: int64 */
            userId?: number;
            /** @enum {string} */
            visibility?: "PUBLIC" | "PRIVATE";
        };
        Share: {
            label?: string;
            /** Format: int32 */
            share?: number;
        };
        SignupRequest: {
            /** Format: date */
            birthDate: string;
            emailVerificationToken: string;
            /** @enum {string} */
            gender: "MALE" | "FEMALE" | "OTHER";
            nickname: string;
            password: string;
        };
        /** @description 기준 영화와 콘텐츠 임베딩이 가까운 영화 */
        SimilarMovieItemResponse: {
            /** @description 전체 사용자 평균 평점 */
            averageRating: number;
            /**
             * Format: int64
             * @description 서비스 영화 ID
             */
            movieId: number;
            /** @description 포스터 URL. 없으면 null */
            posterUrl: string | null;
            /**
             * Format: double
             * @description 코사인 유사도. 1에 가까울수록 유사함
             */
            similarity: number;
            /** @description 영화 제목 */
            title: string;
        };
        /** @description 기준 영화의 콘텐츠 임베딩과 가까운 영화 목록 */
        SimilarMoviesResponse: {
            /**
             * Format: int64
             * @description 기준 서비스 영화 ID
             */
            baseMovieId: number;
            /** @description 코사인 유사도 내림차순 영화 목록 */
            items: components["schemas"]["SimilarMovieItemResponse"][];
        };
        Stats: {
            /** Format: int64 */
            frameCount?: number;
            /** Format: int64 */
            popcornCount?: number;
        };
        Summary: {
            averageScore?: number;
            /** Format: int32 */
            newFlavorCount?: number;
            /** Format: int32 */
            ratingCount?: number;
        };
        TasteAnalysisResponse: {
            analysis?: components["schemas"]["Analysis"];
            /** Format: date-time */
            analyzedAt?: string;
            insufficientData?: boolean;
            keywords?: components["schemas"]["Keyword"][];
            /** Format: int32 */
            ratingCount?: number;
        };
        TokenResponse: {
            accessToken?: string;
            /** Format: int64 */
            expiresIn?: number;
        };
        /** @description 급상승 항목 */
        TrendingMovieItemResponse: {
            /** @example 4.3 */
            averageScore?: number;
            movie?: components["schemas"]["TrendingMovieResponse"];
            /**
             * Format: int32
             * @example 1
             */
            rank?: number;
            /**
             * Format: int32
             * @description 직전 게시 대비 순위 상승 폭. 신규 진입이면 null
             * @example 4
             */
            rankDelta?: number | null;
            /**
             * Format: int32
             * @example 120
             */
            ratingCount?: number;
            /** @description 급상승 점수 (0, 1]. 평점이 아니다. 초기 고정 목록에서는 null */
            trendScore?: number | null;
        };
        /** @description 영화 표시 정보 */
        TrendingMovieResponse: {
            /**
             * Format: int64
             * @example 550
             */
            movieId?: number;
            posterUrl?: string | null;
            /** @example 파이트 클럽 */
            title?: string;
        };
        /** @description 급상승 영화 목록 */
        TrendingMoviesResponse: {
            items?: components["schemas"]["TrendingMovieItemResponse"][];
            period?: components["schemas"]["TrendingPeriodResponse"];
            /**
             * @example VIEW
             * @enum {string}
             */
            source?: "DEFAULT" | "VIEW";
            /**
             * @description 마지막 유효 집계를 제공하는 중이면 true
             * @example false
             */
            stale?: boolean;
            /**
             * Format: date-time
             * @example 2026-09-18T12:00:00Z
             */
            updatedAt?: string | null;
        };
        /** @description 집계 구간. 초기 고정 목록에서는 null */
        TrendingPeriodResponse: {
            /**
             * Format: date-time
             * @example 2026-09-18T11:00:00Z
             */
            from?: string;
            /**
             * Format: date-time
             * @example 2026-09-18T12:00:00Z
             */
            to?: string;
        };
        UserBlockResponse: {
            /** Format: date-time */
            blockedAt?: string;
            /** Format: int64 */
            blockedUserId?: number;
        };
        UserOttResponse: {
            ottIds?: number[];
        };
        UserOttUpdateRequest: {
            ottIds: number[];
        };
        UserProfileResponse: {
            avatarUrl?: string;
            /** Format: date */
            birthDate?: string;
            /** Format: date-time */
            createdAt?: string;
            email?: string;
            /** @enum {string} */
            gender?: "MALE" | "FEMALE" | "OTHER";
            hasPassword?: boolean;
            linkedProviders?: string[];
            nickname?: string;
            onboardingCompleted?: boolean;
            stats?: components["schemas"]["Stats"];
            /** Format: int64 */
            userId?: number;
            /** @enum {string} */
            visibility?: "PUBLIC" | "PRIVATE";
        };
        UserSummary: {
            nickname?: string;
            /** Format: int64 */
            userId?: number;
        };
        /** @description 감상 확인 요청 */
        WatchConfirmRequest: {
            /**
             * @description 실제로 영화를 감상했는지 여부
             * @example true
             */
            watched: boolean;
        };
        /** @description 감상 확인 결과 */
        WatchConfirmResponse: {
            /**
             * @description 확인 후 상태
             * @enum {string}
             */
            status: "WATCHED_UNRATED" | "EXPIRED";
            /**
             * Format: date-time
             * @description 감상 완료 시각(UTC). 안 봤어요 응답이면 null
             */
            watchedAt: string | null;
            /**
             * Format: int64
             * @description 감상 기록 ID
             * @example 5001
             */
            watchRecordId: number;
        };
        WatchRecord: {
            /** Format: date-time */
            clickedAt?: string;
            ott?: components["schemas"]["Ott"];
            /** Format: date-time */
            ratedAt?: string;
            /** Format: date-time */
            watchedAt?: string;
        };
        /** @description 감상 기록 목록 항목 */
        WatchRecordItemResponse: {
            /**
             * Format: date-time
             * @description OTT 클릭 시각(UTC)
             */
            clickedAt: string | null;
            /**
             * Format: date-time
             * @description 감상 확인 노출 시각(UTC, clickedAt과 같다)
             */
            confirmDueAt: string | null;
            /**
             * Format: date-time
             * @description 감상 기록 만료 시각(UTC, clickedAt + 7일)
             */
            expiresAt: string | null;
            /** @description 영화 요약 */
            movie: components["schemas"]["MovieSummary"];
            /** @description 클릭한 OTT 요약. 클릭 이력이 없으면 null */
            ott: components["schemas"]["OttSummary"];
            /**
             * Format: int64
             * @description 연결된 평가 ID. 평가가 없으면 null
             */
            ratingId: number | null;
            /**
             * @description 감상 상태
             * @enum {string}
             */
            status: "LINK_CLICKED" | "WATCHED_UNRATED" | "RATED" | "EXPIRED";
            /**
             * Format: date-time
             * @description 감상 완료 시각(UTC)
             */
            watchedAt: string | null;
            /**
             * Format: int64
             * @description 감상 기록 ID
             * @example 5001
             */
            watchRecordId: number;
        };
        /** @description 감상 기록 cursor 페이지 */
        WatchRecordPageResponse: {
            /** @description 다음 페이지 존재 여부 */
            hasNext: boolean;
            /** @description 현재 페이지의 감상 기록 */
            items: components["schemas"]["WatchRecordItemResponse"][];
            /** @description 다음 페이지 cursor. 마지막 페이지면 null */
            nextCursor: string | null;
        };
        /** @description 브라우저 Web Push 공개 설정 */
        WebPushConfigResponse: {
            enabled?: boolean;
            publicKey?: string | null;
        };
        WebPushSubscriptionRequest: {
            endpoint: string;
            /** Format: int64 */
            expirationTime?: number;
            keys: components["schemas"]["Keys"];
        };
        /** @description 등록된 브라우저 Web Push 구독 */
        WebPushSubscriptionResponse: {
            /**
             * Format: int64
             * @example 42
             */
            subscriptionId?: number;
        };
        /** @description 개봉연도 범위. 날짜가 있는 영화가 없으면 두 값 모두 null */
        YearRange: {
            /**
             * Format: int32
             * @example 2026
             */
            max?: number | null;
            /**
             * Format: int32
             * @example 1920
             */
            min?: number | null;
        };
    };
    responses: never;
    parameters: never;
    requestBodies: never;
    headers: never;
    pathItems: never;
}
export type $defs = Record<string, never>;
export interface operations {
    sendVerificationCode: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["EmailVerificationCodeRequest"];
            };
        };
        responses: {
            /** @description Accepted */
            202: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    verifyVerificationCode: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["EmailVerificationCodeVerifyRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["EmailVerificationTokenResponse"];
                };
            };
        };
    };
    login_1: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["LoginRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["LoginResponse"];
                };
            };
        };
    };
    logout: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    login: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                provider: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["OAuthCodeRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": Record<string, unknown>;
                };
            };
        };
    };
    signup_1: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["OAuthSignupRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["LoginResponse"];
                };
            };
        };
    };
    resetPassword: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["PasswordResetRequest"];
            };
        };
        responses: {
            /** @description No Content */
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    requestPasswordReset: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["PasswordResetMailRequest"];
            };
        };
        responses: {
            /** @description Accepted */
            202: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    privateAuth: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": number;
                };
            };
        };
    };
    signup: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["SignupRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["LoginResponse"];
                };
            };
        };
    };
    refresh: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["TokenResponse"];
                };
            };
        };
    };
    getFrameDetail: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                frameId: number;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["FrameDetailResponse"];
                };
            };
        };
    };
    getDetail: {
        parameters: {
            query?: {
                /** @description 추천 카드의 항목 ID. 양의 64비트 정수(1~9223372036854775807), 생략 가능. 영화 일치·열람 권한 확인 후 추천 이유 반환 */
                recommendationItemId?: number;
            };
            header?: never;
            path: {
                /** @description 서비스 영화 ID. 양의 64비트 정수(1~9223372036854775807) */
                movieId: number;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description 영화 상세 조회 성공 */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["MovieDetailResponse"];
                };
            };
            /** @description VALIDATION_FAILED: ID 형식 오류 또는 양의 64비트 정수 범위 초과 */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description RESOURCE_NOT_FOUND: 존재하지 않는 영화 */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description INTERNAL_ERROR: 내부 데이터 계약 위반 등 서버 오류 */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    /**
                     * @example {
                     *       "code": "INTERNAL_ERROR",
                     *       "message": "서버 내부 오류가 발생했습니다."
                     *     }
                     */
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
        };
    };
    dismiss: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                movieId: number;
            };
            cookie?: never;
        };
        requestBody?: {
            content: {
                "application/json": components["schemas"]["RecommendationDismissRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["RecommendationDismissResponse"];
                };
            };
        };
    };
    click: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                /** @description 서비스 영화 ID. 양의 64비트 정수(1~9223372036854775807) */
                movieId: number;
            };
            cookie?: never;
        };
        /** @description 클릭한 영화 연결 OTT */
        requestBody: {
            content: {
                "application/json": components["schemas"]["OttClickRequest"];
            };
        };
        responses: {
            /** @description LINK_CLICKED 재클릭 갱신 또는 기존 WATCHED_UNRATED·RATED 상태 유지 */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["OttClickResponse"];
                };
            };
            /** @description 활성 기록이 없어 새 LINK_CLICKED 감상 기록 생성 */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["OttClickResponse"];
                };
            };
            /** @description VALIDATION_FAILED: movieId 또는 ottId가 양의 64비트 정수가 아님 */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description UNAUTHORIZED: Access Token이 없거나 사용자 계정이 활성 상태가 아님 */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description RESOURCE_NOT_FOUND: 영화·OTT가 없거나 영화에 OTT가 연결되지 않음 */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description INTERNAL_ERROR: 감상 기록 또는 아웃박스 저장 실패 */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
        };
    };
    create_2: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                /** @description 평가할 영화 ID. 양의 64비트 정수 */
                movieId: number;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["RatingCreateRequest"];
            };
        };
        responses: {
            /** @description 평가 생성 */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RatingCreateResponse"];
                };
            };
            /** @description VALIDATION_FAILED: 요청 값이 평가 계약을 위반함 */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description UNAUTHORIZED: 인증 정보가 없거나 활성 사용자가 아님 */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description RESOURCE_NOT_FOUND: 영화가 존재하지 않음 */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description RATING_ALREADY_EXISTS: 같은 영화에 이미 평가가 존재함 */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description INTERNAL_ERROR: 평가 또는 연관 데이터 저장 실패 */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
        };
    };
    getReviews: {
        parameters: {
            query?: {
                /** @description 서버가 직전 응답에서 발급한 불투명 cursor */
                cursor?: string;
                /** @description 페이지 크기(기본 20, 1~50) */
                size?: number;
            };
            header?: never;
            path: {
                /** @description 서비스 영화 ID. 양의 64비트 정수 */
                movieId: number;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description 공개 한줄평 목록 조회 성공 */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["PublicReviewPageResponse"];
                };
            };
            /** @description VALIDATION_FAILED: 잘못된 movieId·size·cursor */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description UNAUTHORIZED: 인증이 없거나 유효하지 않거나 ACTIVE 회원이 아님; TOKEN_EXPIRED: Access Token이 만료됨 */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description RESOURCE_NOT_FOUND: 존재하지 않는 영화 */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description INTERNAL_ERROR: 한줄평 조회 실패 */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
        };
    };
    getSimilarMovies: {
        parameters: {
            query?: {
                size?: number;
            };
            header?: never;
            path: {
                movieId: number;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["SimilarMoviesResponse"];
                };
            };
        };
    };
    getOptions: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description 필터 옵션 조회 성공 */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["MovieFilterOptionsResponse"];
                };
            };
            /** @description 저장된 국가 코드의 표시명 변환 실패 등 서버 오류 */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
        };
    };
    search_1: {
        parameters: {
            query?: {
                /**
                 * @description ISO 3166-1 alpha-2 국가 코드. 같은 이름을 반복하면 OR로 검색
                 * @example KR
                 */
                country?: string[];
                /** @description 이전 응답의 다음 페이지 cursor */
                cursor?: string;
                /**
                 * @description 장르 코드. 같은 이름을 반복하면 OR로 검색
                 * @example DRAMA
                 */
                genre?: string[];
                /**
                 * @description OTT 식별자. 같은 이름을 반복하면 OR로 검색
                 * @example 1
                 */
                ott?: number[];
                /**
                 * @description 영화 제목·원제·배우·감독 검색어. 필터가 없으면 필수
                 * @example 기생충
                 */
                q?: string;
                /**
                 * @description 페이지 크기(1~50, 기본 20)
                 * @example 20
                 */
                size?: number;
                /**
                 * @description 정렬 기준: RELEVANCE, RATING, RELEASE_DATE
                 * @example RELEVANCE
                 */
                sort?: "RELEVANCE" | "RATING" | "RELEASE_DATE";
                /**
                 * @description 개봉연도 시작값(포함)
                 * @example 2000
                 */
                yearFrom?: number;
                /**
                 * @description 개봉연도 종료값(포함)
                 * @example 2026
                 */
                yearTo?: number;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description 검색 성공. 결과가 없으면 totalCount=0과 빈 items를 반환합니다. */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["MovieSearchPageResponse"];
                };
            };
            /** @description 검색조건 또는 cursor 검증 실패 */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["MovieSearchPageResponse"];
                };
            };
            /** @description Access Token이 없거나 유효하지 않음 */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["MovieSearchPageResponse"];
                };
            };
        };
    };
    getTrending: {
        parameters: {
            query?: {
                /** @description 반환할 영화 수(기본 10, 1~30) */
                size?: number;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description 급상승 영화 조회 성공 */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["TrendingMoviesResponse"];
                };
            };
            /** @description size가 정수가 아니거나 1~30 범위를 벗어남 */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description 게시된 급상승 결과를 조회할 수 없고 초기 고정 목록으로 대체할 수도 없음 */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
        };
    };
    list_2: {
        parameters: {
            query?: {
                /** @description 서버가 직전 응답에서 발급한 불투명 커서 */
                cursor?: string;
                /** @description 페이지 크기(기본 20, 1~50) */
                size?: number;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description 인앱 알림 목록 조회 성공 */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["NotificationPageResponse"];
                };
            };
        };
    };
    dismiss_1: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                notificationId: number;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description 알림 숨김 성공 */
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    markRead: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                notificationId: number;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description 읽음 처리 성공 */
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    config: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description 공개 Web Push 설정 조회 성공 */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["WebPushConfigResponse"];
                };
            };
            /** @description 인증되지 않았거나 비활성 회원 */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ErrorResponse"];
                };
            };
        };
    };
    register: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["WebPushSubscriptionRequest"];
            };
        };
        responses: {
            /** @description 구독 등록 또는 갱신 성공 */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["WebPushSubscriptionResponse"];
                };
            };
            /** @description 구독 정보 검증 실패 */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description 인증되지 않았거나 비활성 회원 */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description Web Push 비활성화 */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ErrorResponse"];
                };
            };
        };
    };
    unregister: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                subscriptionId: number;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description 구독 해제 성공 */
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description 인증되지 않았거나 비활성 회원 */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description 현재 사용자 소유 구독 없음 */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ErrorResponse"];
                };
            };
        };
    };
    markAllRead: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description 전체 읽음 처리 성공 */
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    unreadCount: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["NotificationUnreadCountResponse"];
                };
            };
        };
    };
    getCards: {
        parameters: {
            query: {
                criteria: components["schemas"]["OnboardingCardCriteria"];
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["OnboardingCardListResponse"];
                };
            };
        };
    };
    submit: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["OnboardingSubmitRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["OnboardingSubmitResponse"];
                };
            };
        };
    };
    getOtts: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["OttListResponse"];
                };
            };
        };
    };
    list: {
        parameters: {
            query?: {
                cursor?: string;
                size?: number;
                status?: string;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["CursorPageResponsePartyListItemResponse"];
                };
            };
        };
    };
    create_1: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["PartyCreateRequest"];
            };
        };
        responses: {
            /** @description Created */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["PartyResponse"];
                };
            };
        };
    };
    close: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                partyId: number;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["PartyStateResponse"];
                };
            };
        };
    };
    invite: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                partyId: number;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["PartyInvitationCreateRequest"];
            };
        };
        responses: {
            /** @description Created */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["PartyInvitationResponse"];
                };
            };
        };
    };
    leave: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                partyId: number;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["PartyStateResponse"];
                };
            };
        };
    };
    kick: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                partyId: number;
                userId: number;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["PartyStateResponse"];
                };
            };
        };
    };
    getPartyPopcorn: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                partyId: number;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["PartyPopcornResponse"];
                };
            };
        };
    };
    recommendations: {
        parameters: {
            query?: {
                refresh?: boolean;
                size?: number;
            };
            header?: never;
            path: {
                partyId: number;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["PartyRecommendationResponse"];
                };
            };
        };
    };
    recommendationHistory: {
        parameters: {
            query?: {
                cursor?: string;
                size?: number;
            };
            header?: never;
            path: {
                partyId: number;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["CursorPageResponsePartyRecommendationHistorySetResponse"];
                };
            };
        };
    };
    listInvitations: {
        parameters: {
            query?: {
                cursor?: string;
                size?: number;
                status?: string;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["CursorPageResponseReceivedPartyInvitationResponse"];
                };
            };
        };
    };
    acceptInvitation: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                invitationId: number;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["PartyResponse"];
                };
            };
        };
    };
    declineInvitation: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                invitationId: number;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["PartyInvitationStatusResponse"];
                };
            };
        };
    };
    getPopcornFlavors: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["PopcornFlavorListResponse"];
                };
            };
        };
    };
    update: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                /** @description 본인 평가 ID. 양의 64비트 정수 */
                ratingId: number;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["RatingUpdateRequest"];
            };
        };
        responses: {
            /** @description 수정된 평가 */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RatingCreateResponse"];
                };
            };
            /** @description VALIDATION_FAILED: 경로 ID 또는 요청 본문 오류 */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description UNAUTHORIZED: 인증 정보가 없거나 활성 사용자가 아님 */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description RESOURCE_NOT_FOUND: 평가가 없거나 다른 사용자의 평가 */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description UNSUPPORTED_MEDIA_TYPE: Content-Type 누락 또는 비지원 */
            415: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description INTERNAL_ERROR: 평가 또는 연관 데이터 저장 실패 */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
        };
    };
    delete_1: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                /** @description 본인 평가 ID. 양의 64비트 정수 */
                ratingId: number;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description 평가 삭제 */
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description VALIDATION_FAILED: 경로 ID 오류 */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description UNAUTHORIZED: 인증 정보가 없거나 활성 사용자가 아님 */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description RESOURCE_NOT_FOUND: 평가가 없거나 다른 사용자의 평가 */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description INTERNAL_ERROR: 평가 또는 연관 데이터 삭제 실패 */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
        };
    };
    getRecommendations: {
        parameters: {
            query?: {
                size?: string;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["RecommendationResponse"];
                };
            };
        };
    };
    getHistory: {
        parameters: {
            query?: {
                cursor?: string;
                size?: number;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["CursorPageResponseRecommendationHistorySetResponse"];
                };
            };
        };
    };
    getOttRecommendations: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["OttRecommendationResponse"];
                };
            };
        };
    };
    download: {
        parameters: {
            query?: {
                expires?: string;
                signature?: string;
            };
            header?: never;
            path: {
                imageId: number;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    detail: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                reportId: number;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ReportDetailResponse"];
                };
            };
        };
    };
    create: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                reportId: number;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ReportImageResponse"];
                };
            };
        };
    };
    issue: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                reportId: number;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ReportIssueResponse"];
                };
            };
        };
    };
    getPublicProfile: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                /** @description users.public_id(UUID). 내부 숫자 ID는 공개 URL에 쓰지 않는다(ADR-0008) */
                publicId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description 공개 프로필 조회 성공 */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["PublicProfileResponse"];
                };
            };
            /** @description RESOURCE_NOT_FOUND: 없는 사용자·PRIVATE 사용자·차단 관계 */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description INTERNAL_ERROR: 공개 프로필 조회 실패 */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
        };
    };
    block: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                userId: number;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Created */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["UserBlockResponse"];
                };
            };
        };
    };
    unblock: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                userId: number;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description No Content */
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    getProfile: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["UserProfileResponse"];
                };
            };
        };
    };
    withdraw: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["AccountWithdrawalRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    updateProfile: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ProfileUpdateRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["UserProfileResponse"];
                };
            };
        };
    };
    upload: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: {
            content: {
                "multipart/form-data": {
                    /** Format: binary */
                    file: string;
                };
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["AvatarResponse"];
                };
            };
        };
    };
    delete: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description No Content */
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    blocks: {
        parameters: {
            query?: {
                cursor?: string;
                size?: number;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["CursorPageResponseBlockedUserResponse"];
                };
            };
        };
    };
    getMyFrames: {
        parameters: {
            query?: {
                cursor?: string;
                flavorCode?: string;
                size?: number;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["FrameListResponse"];
                };
            };
        };
    };
    getMySettings: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["NotificationSettingsResponse"];
                };
            };
        };
    };
    replaceMySettings: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["NotificationSettingsUpdateRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["NotificationSettingsResponse"];
                };
            };
        };
    };
    link: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                provider: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["OAuthCodeRequest"];
            };
        };
        responses: {
            /** @description Created */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["OAuthLinkResponse"];
                };
            };
        };
    };
    getMyOtts: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["UserOttResponse"];
                };
            };
        };
    };
    replaceMyOtts: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["UserOttUpdateRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["UserOttResponse"];
                };
            };
        };
    };
    changePassword: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["PasswordChangeRequest"];
            };
        };
        responses: {
            /** @description No Content */
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    getMyPopcornBucket: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["PopcornBucketResponse"];
                };
            };
        };
    };
    getMyRatings: {
        parameters: {
            query?: {
                /** @description 서버가 직전 응답에서 발급한 불투명 cursor */
                cursor?: string;
                /** @description 생략 시 평가만; ALL, COMMENTED, UNRATED */
                filter?: "ALL" | "COMMENTED" | "UNRATED";
                /** @description 페이지 크기(기본 20, 1~50) */
                size?: number;
                /** @description 정렬(기본 LATEST) */
                sort?: "LATEST" | "SCORE_DESC" | "SCORE_ASC" | "OLDEST";
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description 내 평가 목록 조회 성공 */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["MyRatingPageResponse"];
                };
            };
            /** @description VALIDATION_FAILED: 잘못된 filter·sort·size·cursor */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description UNAUTHORIZED: 인증이 없거나 유효하지 않거나 ACTIVE 회원이 아님; TOKEN_EXPIRED: Access Token 만료 */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description INTERNAL_ERROR: 평가 목록 조회 실패 */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
        };
    };
    list_1: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ReportListResponse"];
                };
            };
        };
    };
    getMyTasteAnalysis: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["TasteAnalysisResponse"];
                };
            };
        };
    };
    check: {
        parameters: {
            query: {
                nickname: string;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["NicknameAvailabilityResponse"];
                };
            };
        };
    };
    search: {
        parameters: {
            query?: {
                cursor?: string;
                nickname?: string;
                size?: number;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["CursorPageResponseSearchedUserResponse"];
                };
            };
        };
    };
    getWatchRecords: {
        parameters: {
            query?: {
                /** @description true이면 확인 시각(clickedAt과 같다)에 도달했고 만료 시각(7일) 전인 LINK_CLICKED 기록만 조회 */
                confirmDue?: boolean;
                /** @description 서버가 직전 응답에서 발급한 불투명 커서 */
                cursor?: string;
                /** @description 페이지 크기(기본 20, 1~50) */
                size?: number;
                /** @description 감상 상태. status=A&status=B처럼 이름을 반복해 전달하며 중복은 제거됩니다. 인덱스·중첩 표기는 400입니다. */
                status?: ("LINK_CLICKED" | "WATCHED_UNRATED" | "RATED" | "EXPIRED")[];
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description 감상 기록 목록 조회 성공 */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["WatchRecordPageResponse"];
                };
            };
            /** @description VALIDATION_FAILED: 잘못된 상태·confirmDue·size·cursor, 인덱스·중첩 파라미터 표기 */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description UNAUTHORIZED: 인증이 없거나 활성 사용자가 아님 */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description INTERNAL_ERROR: 조회 또는 만료 반영 실패 */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
        };
    };
    confirm: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                /** @description 본인 감상 기록 ID. 양의 64비트 정수 */
                watchRecordId: number;
            };
            cookie?: never;
        };
        /** @description 감상 여부. watched는 JSON boolean이어야 합니다. */
        requestBody: {
            content: {
                "application/json": components["schemas"]["WatchConfirmRequest"];
            };
        };
        responses: {
            /** @description 감상 확인 반영 성공 */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["WatchConfirmResponse"];
                };
            };
            /** @description VALIDATION_FAILED: 경로 ID 또는 JSON boolean 본문 오류, 인덱스·중첩 파라미터 표기 */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description UNAUTHORIZED: 인증이 없거나 활성 사용자가 아님 */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description RESOURCE_NOT_FOUND: 기록이 없거나 다른 사용자의 기록 */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description INVALID_WATCH_STATUS: 요청에 허용되지 않는 상태, 클릭 응답 기한 경과 또는 경쟁 전이 */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description UNSUPPORTED_MEDIA_TYPE: Content-Type 누락 또는 비지원 */
            415: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description INTERNAL_ERROR: 상태 전이 저장 또는 commit 실패 */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
        };
    };
}
