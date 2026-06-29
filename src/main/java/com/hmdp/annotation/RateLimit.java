package com.hmdp.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static com.hmdp.utils.SystemConstants.DEFAULT_RATE_LIMIT_MESSAGE;
import static com.hmdp.utils.SystemConstants.RATE_LIMIT_KEY_PREFIX;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {
    String keyPrefix() default RATE_LIMIT_KEY_PREFIX;

    int maxRequests() default 5;

    int windowSeconds() default 1;

    String message() default DEFAULT_RATE_LIMIT_MESSAGE;
}
