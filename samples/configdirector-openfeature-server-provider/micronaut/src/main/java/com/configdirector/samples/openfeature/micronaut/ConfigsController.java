package com.configdirector.samples.openfeature.micronaut;

import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.ImmutableContext;
import dev.openfeature.sdk.ImmutableStructure;
import dev.openfeature.sdk.Value;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Controller("/configs")
public class ConfigsController {

  private static final Set<String> CONTEXT_FIELDS = Set.of("id", "name", "anonymous");
  private static final Value EMPTY_OBJECT = new Value(new ImmutableStructure());

  private final Client client;

  public ConfigsController(Client client) {
    this.client = client;
  }

  @Get
  public Map<String, Object> configs(HttpRequest<?> request) {
    EvaluationContext context =
        contextFrom(request.getParameters().asMap(String.class, String.class));

    Map<String, Object> configs = new LinkedHashMap<>();
    configs.put(
        "temporary-feature-flag", client.getBooleanValue("temporary-feature-flag", true, context));
    configs.put(
        "permanent-kill-switch", client.getBooleanValue("permanent-kill-switch", false, context));
    configs.put("integer-config", client.getIntegerValue("integer-config", 10, context));
    configs.put(
        "day-of-the-week-config", client.getStringValue("day-of-the-week-config", "Friday", context));
    configs.put(
        "json-value-config",
        client.getObjectValue("json-value-config", EMPTY_OBJECT, context).asStructure().asObjectMap());
    return configs;
  }

  private static EvaluationContext contextFrom(Map<String, String> query) {
    Map<String, Value> traits = new LinkedHashMap<>();
    query.forEach(
        (key, value) -> {
          if (!CONTEXT_FIELDS.contains(key)) {
            traits.put(key, new Value(value));
          }
        });

    Map<String, Value> attributes = new LinkedHashMap<>();
    if (query.get("name") != null) {
      attributes.put("name", new Value(query.get("name")));
    }
    attributes.put("anonymous", new Value("true".equals(query.get("anonymous"))));
    attributes.put("traits", new Value(new ImmutableStructure(traits)));
    return new ImmutableContext(query.get("id"), attributes);
  }
}
