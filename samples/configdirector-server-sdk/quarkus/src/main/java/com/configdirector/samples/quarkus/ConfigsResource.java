package com.configdirector.samples.quarkus;

import com.configdirector.ConfigDirectorClient;
import com.configdirector.Context;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.UriInfo;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Evaluates a handful of configs and returns them as JSON.
 *
 * <p>Query parameters double as the evaluation context: {@code id}, {@code name} and {@code
 * anonymous} map onto the matching {@link Context} fields, and anything else becomes a trait.
 */
@Path("/configs")
public class ConfigsResource {

  /** Query parameters that describe the user rather than one of their traits. */
  private static final Set<String> CONTEXT_FIELDS = Set.of("id", "name", "anonymous");

  private final ConfigDirectorClient client;

  /** The one client, injected. Never build one per request. */
  public ConfigsResource(ConfigDirectorClient client) {
    this.client = client;
  }

  // Quarkus REST injects UriInfo without the jakarta.ws.rs.core.Context annotation JAX-RS would
  // normally require, which is handy here: that annotation and com.configdirector.Context share a
  // simple name, so one of the two would have to be spelled out in full.
  @GET
  @Produces(MediaType.APPLICATION_JSON)
  public Map<String, Object> configs(UriInfo uriInfo) {
    Context context = contextFrom(uriInfo.getQueryParameters());

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
  private static Context contextFrom(MultivaluedMap<String, String> query) {
    Context.Builder context =
        Context.builder()
            .id(query.getFirst("id"))
            .name(query.getFirst("name"))
            .anonymous("true".equals(query.getFirst("anonymous")));

    query.forEach(
        (key, values) -> {
          if (!CONTEXT_FIELDS.contains(key) && !values.isEmpty()) {
            context.trait(key, values.get(0));
          }
        });
    return context.build();
  }
}
