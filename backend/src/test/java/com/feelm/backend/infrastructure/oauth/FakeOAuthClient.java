package com.feelm.backend.infrastructure.oauth;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * provider 호출을 대신하는 테스트용 {@link OAuthClient}이다.
 *
 * <p>계약 테스트가 실제 Google·Kakao·Naver에 접속하지 않도록 코드 교환 지점을 대체한다. 응답과 실패를 테스트가 지정하고, 호출 인자를 기록해 "허용 목록 밖
 * redirectUri면 provider를 호출하지 않는다"처럼 호출 여부 자체가 계약인 경우도 검증할 수 있게 한다.
 */
public class FakeOAuthClient implements OAuthClient {

    private final List<Call> calls = new CopyOnWriteArrayList<>();

    private volatile OAuthUserProfile profile;
    private volatile RuntimeException failure;

    /** 다음 호출이 돌려줄 사용자 정보를 지정한다. */
    public void willReturn(OAuthUserProfile profile) {
        this.profile = profile;
        this.failure = null;
    }

    /** 다음 호출이 던질 예외를 지정한다. 코드 교환 실패와 provider 오류 경로에 쓴다. */
    public void willFail(RuntimeException failure) {
        this.failure = failure;
        this.profile = null;
    }

    /** 지금까지의 호출 인자. 호출이 일어나지 않아야 하는 경로에서 비어 있음을 확인한다. */
    public List<Call> calls() {
        return List.copyOf(calls);
    }

    public void reset() {
        calls.clear();
        profile = null;
        failure = null;
    }

    @Override
    public OAuthUserProfile fetchProfile(String code, String redirectUri) {
        calls.add(new Call(code, redirectUri));
        if (failure != null) {
            throw failure;
        }
        if (profile == null) {
            throw new IllegalStateException("테스트가 provider 응답을 지정하지 않았습니다.");
        }
        return profile;
    }

    /** 한 번의 코드 교환 요청. */
    public record Call(String code, String redirectUri) {}
}
