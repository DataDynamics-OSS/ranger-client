package io.datadynamics.ranger.client.spring;

import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.util.List;
import java.util.Map;

class OnRangerServerCondition extends SpringBootCondition {
    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Map<String, Object> attrs = metadata.getAnnotationAttributes(ConditionalOnRangerServer.class.getName());
        String key = "ranger." + attrs.get("value") + ".urls";
        List<String> urls = Binder.get(context.getEnvironment()).bind(key, Bindable.listOf(String.class)).orElse(List.of());
        return urls.isEmpty() ? ConditionOutcome.noMatch(key + " is not set") : ConditionOutcome.match(key + " " + urls);
    }
}
