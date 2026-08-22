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

  /**
   * Starts an empty context.
   *
   * @return a builder to add an id, a name, and traits to
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * A context carrying nothing, which matches only rules that need no user detail.
   *
   * @return the shared empty context
   */
  public static Context empty() {
    return EMPTY;
  }

  /**
   * The user's identifier.
   *
   * @return the id, or null, in which case a percentage rollout assigns an unstable bucket
   */
  public String id() {
    return id;
  }

  /**
   * The user's display name.
   *
   * @return the name, or null
   */
  public String name() {
    return name;
  }

  /**
   * The user's traits, evaluated against targeting rules.
   *
   * @return the traits, unmodifiable, or null when none were set
   */
  public Map<String, Object> traits() {
    return traits;
  }

  /**
   * Whether the context stays out of the dashboard.
   *
   * @return true when the context is evaluated but never persisted
   */
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

  /** Collects the user's details. Every setter returns this, so calls chain. */
  public static final class Builder {

    private String id;
    private String name;
    private Map<String, Object> traits;
    private boolean anonymous;

    private Builder() {}

    /**
     * The user's identifier, which decides their bucket in a percentage rollout.
     *
     * @param id the identifier, or null for an unstable bucket
     * @return this builder, so calls chain
     */
    public Builder id(String id) {
      this.id = id;
      return this;
    }

    /**
     * The user's display name.
     *
     * @param name the name, or null
     * @return this builder, so calls chain
     */
    public Builder name(String name) {
      this.name = name;
      return this;
    }

    /**
     * Sets every trait at once, replacing any set so far.
     *
     * @param traits JSON-shaped values, or null to clear them
     * @return this builder, so calls chain
     */
    public Builder traits(Map<String, Object> traits) {
      this.traits = traits == null ? null : new HashMap<>(traits);
      return this;
    }

    /**
     * Adds one trait, keeping the rest.
     *
     * @param key the trait's name, as targeting rules reference it
     * @param value a JSON-shaped value
     * @return this builder, so calls chain
     */
    public Builder trait(String key, Object value) {
      if (traits == null) {
        traits = new HashMap<>();
      }
      traits.put(key, value);
      return this;
    }

    /**
     * Keeps the context out of the dashboard: it is evaluated but never persisted, and telemetry
     * reports neither the context nor its id.
     *
     * @param anonymous true to evaluate without persisting
     * @return this builder, so calls chain
     */
    public Builder anonymous(boolean anonymous) {
      this.anonymous = anonymous;
      return this;
    }

    /**
     * Builds the context. Traits are copied, so the builder can be reused.
     *
     * @return the context as configured
     */
    public Context build() {
      return new Context(this);
    }
  }
}
