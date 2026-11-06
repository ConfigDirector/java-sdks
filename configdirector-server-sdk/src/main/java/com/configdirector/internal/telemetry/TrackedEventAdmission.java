package com.configdirector.internal.telemetry;

import com.configdirector.Context;
import com.configdirector.TrackOptions;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public sealed interface TrackedEventAdmission {

  List<TrackedEventWarning> warnings();

  record Admitted(TrackedEventEntry entry, Context context, List<TrackedEventWarning> warnings)
      implements TrackedEventAdmission {}

  record Dropped(List<TrackedEventWarning> warnings) implements TrackedEventAdmission {}

  static TrackedEventAdmission of(
      String name, Context context, TrackOptions options, Instant happenedAt) {
    if (!TrackedEventLimits.isNameWithinTheCharacterLimit(name)) {
      return new Dropped(List.of(TrackedEventWarning.NAME_OUTSIDE_THE_CHARACTER_LIMIT));
    }

    ReportableOption<Number> value = TrackedEventLimits.reportableValueOf(options.value());
    ReportableOption<Map<String, Object>> details =
        TrackedEventLimits.reportableDetailsOf(options.details());
    ReportedContext reportedContext = ReportedContext.of(context);
    return new Admitted(
        new TrackedEventEntry(
            name,
            reportedContext.kind(),
            reportedContext.identifier(),
            value.reported(),
            details.reported(),
            happenedAt),
        context,
        Stream.concat(value.warnings().stream(), details.warnings().stream()).toList());
  }
}
