package com.feelm.backend.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.parallel.ResourceLock;

/** 공유 Spring Context와 datastore를 사용하는 테스트 클래스를 서로 직렬화한다. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@ResourceLock(SharedTestRuntime.RESOURCE)
public @interface SharedTestRuntime {
    String RESOURCE = "backend-shared-test-runtime";
}
