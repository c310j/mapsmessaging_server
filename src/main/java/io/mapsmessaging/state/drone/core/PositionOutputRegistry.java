/*
 *
 *  Copyright [ 2026 ] Ralf Himmelein and Claude
 *
 *  Licensed under the Apache License, Version 2.0 with the Commons Clause
 *  (the "License"); you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *      https://commonsclause.com/
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package io.mapsmessaging.state.drone.core;

import io.mapsmessaging.state.drone.model.GeoPosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Static hook between the twin output paths (CoT composition in {@code TakEventMapper} /
 * {@code CotEventPolicy} and the twin JSON publisher) and an optional external
 * {@code StateMessageAdapter} jar that wants to change the position those outputs report.
 *
 * <p>Output only: the twin itself always keeps the position it was given, so every in-process
 * consumer (KPIs, REST, readiness checks) still sees it. Same late-registration pattern as
 * {@code MtiStatusRegistry} - the adapter registers itself at its own {@code start()}.
 *
 * <p>With no filter registered, or for any twin the filter returns {@code null} for,
 * {@link #apply} returns the position it was given, unchanged.
 */
public final class PositionOutputRegistry {

  /** Implemented by whatever adapter is currently registered. */
  public interface Filter {
    /**
     * @param twinId the twin's {@code EntityTwin.getTwinId()}
     * @param position the twin's own position, never {@code null}; must not be modified
     * @return the position to report for this twin, or {@code null} to report it unchanged
     */
    GeoPosition apply(String twinId, GeoPosition position);
  }

  private static final Logger LOGGER = LoggerFactory.getLogger(PositionOutputRegistry.class);
  private static final AtomicReference<Filter> FILTER = new AtomicReference<>();

  private PositionOutputRegistry() {
  }

  public static void setFilter(Filter filter) {
    FILTER.set(filter);
  }

  public static GeoPosition apply(String twinId, GeoPosition position) {
    Filter current = FILTER.get();
    if (current == null || twinId == null || position == null) {
      return position;
    }
    try {
      GeoPosition reported = current.apply(twinId, position);
      return reported == null ? position : reported;
    } catch (RuntimeException e) {
      // A broken filter must never stop the twin from being published.
      LOGGER.warn("Position output filter failed for {}, reporting the twin's own position", twinId, e);
      return position;
    }
  }
}
