package com.configdirector.samples.micronaut;

import com.configdirector.ConfigDirectorClient;
import com.configdirector.Context;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Evaluates a handful of configs and returns them as JSON.
 *
 * <p>Query parameters double as the evaluation context: {@code id}, {@code name} and {@code
 * anonymous} map onto the matching {@link Context} fields, and anything else becomes a trait.
 */
@Controller("/configs")
public class ConfigsController {

  /** Query parameters that describe the user rather than one of their traits. */
  private static final Set<String> CONTEXT_FIELDS = Set.of("id", "name", "anonymous");

  private final ConfigDirectorClient client;

  /** The one client, injected. Never build one per request. */
  public ConfigsController(ConfigDirectorClient client) {
    this.client = client;
  }

  // Micronaut binds a named parameter per argument, so the whole query string comes off the
  // request itself rather than out of a Map argument.
  @Get
  public Map<String, Object> configs(HttpRequest<?> request) {
    Context context = contextFrom(request.getParameters().asMap(String.class, String.class));

    // Each call evaluates against config state the client already holds in memory, with no
    // network call on the request path. That is what makes it safe to call several times here.
    //
    // The default is what this app serves whenever ConfigDirector is unreachable, so it should
    // always be the safe choice. Its type also decides how the config value is parsed.
    Map<String, Object> configs = new LinkedHashMap<>();
    configs.put("temporary-feature-flag", client.getBoolean("temporary-feature-flag", true, context));
    configs.put("permanent-kill-switch", client.getBoolean("permanent-kill-switch", false, context));
    configs.put("integer-config", client.getInteger("integer-config", 10, context));
    configs.put("day-of-the-week-config", client.getString("day-of-the-week-config", "Friday", context));
    configs.put("json-value-config", client.getJsonObject("json-value-config", Map.of(), context));
    return configs;
  }

  /**
   * The context is per request; the client that evaluates it is not. A real application would
   * build this from the authenticated session.
   */
  private static Context contextFrom(Map<String, String> query) {
    Context.Builder context =
        Context.builder()
            .id(query.get("id"))
            .name(query.get("name"))
            .anonymous("true".equals(query.get("anonymous")));

    query.forEach(
        (key, value) -> {
          if (!CONTEXT_FIELDS.contains(key)) {
            context.trait(key, value);
          }
        });
    return context.build();
  }
}
