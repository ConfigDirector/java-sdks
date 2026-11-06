package com.configdirector.internal.telemetry;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

final class TrackedEventQueue {

  private final List<TrackedEventEntry> entries = new ArrayList<>();
  private final Set<TrackedEventWarning> warningsRaisedThisBatch =
      EnumSet.noneOf(TrackedEventWarning.class);

  synchronized List<TrackedEventWarning> add(TrackedEventAdmission admission) {
    return keepWithinTheBatchLimit(admission).stream().filter(this::isFirstThisBatch).toList();
  }

  synchronized List<TrackedEventEntry> takeBatch() {
    List<TrackedEventEntry> batch = List.copyOf(entries);
    clear();
    return batch;
  }

  synchronized void clear() {
    entries.clear();
    warningsRaisedThisBatch.clear();
  }

  private List<TrackedEventWarning> keepWithinTheBatchLimit(TrackedEventAdmission admission) {
    if (admission instanceof TrackedEventAdmission.Admitted admitted) {
      if (entries.size() >= TrackedEventLimits.EVENTS_PER_BATCH) {
        return List.of(TrackedEventWarning.BATCH_ALREADY_FULL);
      }
      entries.add(admitted.entry());
    }
    return admission.warnings();
  }

  private boolean isFirstThisBatch(TrackedEventWarning warning) {
    return warningsRaisedThisBatch.add(warning);
  }
}
