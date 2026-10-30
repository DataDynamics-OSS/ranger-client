package io.datadynamics.ranger.client.spring;

import org.springframework.context.annotation.Conditional;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Matches when {@code ranger.<value>.urls} has at least one entry. */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Documented
@Conditional(OnRangerServerCondition.class)
@interface ConditionalOnRangerServer {
    /** {@code admin}, {@code pdp} or {@code kms}. */
    String value();
}
