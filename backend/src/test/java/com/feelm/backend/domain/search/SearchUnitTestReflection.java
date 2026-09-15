package com.feelm.backend.domain.search;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** 구현 전에도 전체 테스트를 컴파일하기 위한 호출 어댑터. 대상 동작은 대체하지 않는다. */
final class SearchUnitTestReflection {

    private SearchUnitTestReflection() {}

    static Class<?> searchType(String name) throws ClassNotFoundException {
        // 부재를 성공이나 skip으로 처리하지 않는다. 구현 전에는 ClassNotFoundException으로 Red다.
        return Class.forName("com.feelm.backend.domain.search." + name);
    }

    static Object invoke(Method method, Object target, Object... arguments) throws Throwable {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException exception) {
            // 잘못된 cursor 검증이 리플렉션 래퍼가 아닌 실제 BusinessException을 검사하게 한다.
            throw exception.getCause();
        }
    }
}
