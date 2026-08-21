package com.configdirector;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The user's context, evaluated against targeting rules.
 *
 * <p>{@code id} segments users in percentage rollouts, so changing it can move a user into a
 * different percentile. Trait values are JSON-shaped: {@link String}, {@link Number}, {@link
 * Boolean}, {@link java.util.List}, {@link java.util.Map}, or null. Anything else has no text form
 * and will not match a targeting rule.
 */
public final class Context {

  private static final Context EMPTY = builder().build();

  private final String id;
  private final String name;
  private final Map<String, Object> traits;
  private final boolean anonymous;

  private Context(Builder builder) {
    this.id = builder.id;
    this.name = builder.name;
    // Copied rather than wrapped, so that reusing the builder cannot alter a context already
    // built from it. Map.copyOf is not usable here: a trait may legitimately be JSON null.
    this.traits =
        builder.traits == null
            ? null
            : Collections.unmodifiableMap(new HashMap<>(builder.traits));
    this.anonymous = builder.anonymous;
  }

  public static Builder builder() {
    return new Builder();
  }

  public static Context empty() {
    return EMPTY;
  }

  /** May be null, in which case a percentage rollout assigns an unstable bucket. */
  public String id() {
    return id;
  }

  /** May be null. */
  public String name() {
    return name;
  }

  /** May be null. Unmodifiable when present. */
  public Map<String, Object> traits() {
    return traits;
  }

  /** When true the context is evaluated but never persisted, so it stays out of the dashboard. */
  public boolean anonymous() {
    return anonymous;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    return other instanceof Context that
        && anonymous == that.anonymous
        && Objects.equals(id, that.id)
        && Objects.equals(name, that.name)
        && Objects.equals(traits, that.traits);
  }

  @Override
  public int hashCode() {
    return Objects.hash(id, name, traits, anonymous);
  }

  @Override
  public String toString() {
    return "Context[id=" + id + ", name=" + name + ", traits=" + traits + ", anonymous=" + anonymous + "]";
  }

  public static final class Builder {

    private String id;
    private String name;
    private Map<String, Object> traits;
    private boolean anonymous;

    private Builder() {}

    public Builder id(String id) {
      this.id = id;
      return this;
    }

    public Builder name(String name) {
      this.name = name;
      return this;
    }

    /** Replaces any traits set so far. */
    public Builder traits(Map<String, Object> traits) {
      this.traits = traits == null ? null : new HashMap<>(traits);
      return this;
    }

    public Builder trait(String key, Object value) {
      if (traits == null) {
        traits = new HashMap<>();
      }
      traits.put(key, value);
      return this;
    }

    public Builder anonymous(boolean anonymous) {
      this.anonymous = anonymous;
      return this;
    }

    public Context build() {
      return new Context(this);
    }
  }
}
