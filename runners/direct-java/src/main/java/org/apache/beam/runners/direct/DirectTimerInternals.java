/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.runners.direct;

import org.apache.beam.runners.core.StateNamespace;
import org.apache.beam.runners.core.TimerInternals;
import org.apache.beam.runners.direct.WatermarkManager.TimerUpdate;
import org.apache.beam.runners.direct.WatermarkManager.TimerUpdate.TimerUpdateBuilder;
import org.apache.beam.runners.direct.WatermarkManager.TransformWatermarks;
import org.apache.beam.sdk.state.TimeDomain;
import org.apache.beam.sdk.transforms.windowing.BoundedWindow;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.joda.time.Instant;

/** An implementation of {@link TimerInternals} where all relevant data exists in memory. */
@SuppressWarnings({
  "rawtypes" // TODO(https://issues.apache.org/jira/browse/BEAM-10556)
})
class DirectTimerInternals implements TimerInternals {
  private final Clock processingTimeClock;
  private final TransformWatermarks watermarks;
  private final TimerUpdateBuilder timerUpdateBuilder;

  public static DirectTimerInternals create(
      Clock clock, TransformWatermarks watermarks, TimerUpdateBuilder timerUpdateBuilder) {
    return new DirectTimerInternals(clock, watermarks, timerUpdateBuilder);
  }

  private DirectTimerInternals(
      Clock clock, TransformWatermarks watermarks, TimerUpdateBuilder timerUpdateBuilder) {
    this.processingTimeClock = clock;
    this.watermarks = watermarks;
    this.timerUpdateBuilder = timerUpdateBuilder;
  }

  @Override
  public void setTimer(
      StateNamespace namespace,
      String timerId,
      String timerFamilyId,
      Instant target,
      Instant outputTimestamp,
      TimeDomain timeDomain) {
    timerUpdateBuilder.setTimer(
        TimerData.of(timerId, timerFamilyId, namespace, target, outputTimestamp, timeDomain));
  }

  /**
   * @deprecated use {@link #setTimer(StateNamespace, String, String, Instant, Instant,
   *     TimeDomain)}.
   */
  @Deprecated
  @Override
  public void setTimer(TimerData timerData) {
    timerUpdateBuilder.setTimer(timerData);
  }

  @Override
  public void deleteTimer(
      StateNamespace namespace, String timerId, String timerFamilyId, TimeDomain timeDomain) {
    deleteTimer(
        TimerData.of(
            timerId,
            timerFamilyId,
            namespace,
            BoundedWindow.TIMESTAMP_MIN_VALUE,
            BoundedWindow.TIMESTAMP_MAX_VALUE,
            timeDomain));
  }

  /** @deprecated use {@link #deleteTimer(StateNamespace, String, TimeDomain)}. */
  @Deprecated
  @Override
  public void deleteTimer(StateNamespace namespace, String timerId, String timerFamilyId) {
    throw new UnsupportedOperationException("Canceling of timer by ID is not yet supported.");
  }

  /** @deprecated use {@link #deleteTimer(StateNamespace, String, TimeDomain)}. */
  @Deprecated
  @Override
  public void deleteTimer(TimerData timerKey) {
    timerUpdateBuilder.deletedTimer(timerKey);
  }

  public TimerUpdate getTimerUpdate() {
    return timerUpdateBuilder.build();
  }

  public boolean containsUpdateForTimeBefore(
      Instant maxWatermarkTime, Instant maxProcessingTime, Instant maxSynchronizedProcessingTime) {
    TimerUpdate update = timerUpdateBuilder.build();
    return hasTimeBefore(
            update.getSetTimers(),
            maxWatermarkTime,
            maxProcessingTime,
            maxSynchronizedProcessingTime)
        || hasTimeBefore(
            update.getDeletedTimers(),
            maxWatermarkTime,
            maxProcessingTime,
            maxSynchronizedProcessingTime);
  }

  @Override
  public Instant currentProcessingTime() {
    return processingTimeClock.now();
  }

  @Override
  public @Nullable Instant currentSynchronizedProcessingTime() {
    return watermarks.getSynchronizedProcessingInputTime();
  }

  @Override
  public Instant currentInputWatermarkTime() {
    return watermarks.getInputWatermark();
  }

  @Override
  public @Nullable Instant currentOutputWatermarkTime() {
    return watermarks.getOutputWatermark();
  }

  private boolean hasTimeBefore(
      Iterable<? extends TimerData> timers,
      Instant maxWatermarkTime,
      Instant maxProcessingTime,
      Instant maxSynchronizedProcessingTime) {
    for (TimerData timerData : timers) {
      Instant currentTime;
      switch (timerData.getDomain()) {
        case EVENT_TIME:
          currentTime = maxWatermarkTime;
          break;
        case PROCESSING_TIME:
          currentTime = maxProcessingTime;
          break;
        case SYNCHRONIZED_PROCESSING_TIME:
          currentTime = maxSynchronizedProcessingTime;
          break;
        default:
          throw new RuntimeException("Unexpected timeDomain " + timerData.getDomain());
      }
      if (timerData.getTimestamp().isBefore(currentTime)
          || timerData.getTimestamp().isEqual(currentTime)) {
        return true;
      }
    }
    return false;
  }
}
