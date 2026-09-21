package com.configdirector.samples.openfeature.quarkus;

import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.ImmutableContext;
import dev.openfeature.sdk.ImmutableStructure;
import dev.openfeature.sdk.Value;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.UriInfo;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Path("/configs")
public class ConfigsResource {

  private static final Set<String> CONTEXT_FIELDS = Set.of("id", "name", "anonymous");
  private static final Value EMPTY_OBJECT = new Value(new ImmutableStructure());

  private final Client client;

  public ConfigsResource(Client client) {
    this.client = client;
  }

  @GET
  @Produces(MediaType.APPLICATION_JSON)
  public Map<String, Object> configs(UriInfo uriInfo) {
    EvaluationContext context = contextFrom(uriInfo.getQueryParameters());

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

  private static EvaluationContext contextFrom(MultivaluedMap<String, String> query) {
    Map<String, Value> traits = new LinkedHashMap<>();
    query.forEach(
        (key, values) -> {
          if (!CONTEXT_FIELDS.contains(key) && !values.isEmpty()) {
            traits.put(key, new Value(values.get(0)));
          }
        });

    Map<String, Value> attributes = new LinkedHashMap<>();
    if (query.getFirst("name") != null) {
      attributes.put("name", new Value(query.getFirst("name")));
    }
    attributes.put("anonymous", new Value("true".equals(query.getFirst("anonymous"))));
    attributes.put("traits", new Value(new ImmutableStructure(traits)));
    return new ImmutableContext(query.getFirst("id"), attributes);
  }
}
