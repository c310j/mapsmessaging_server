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

package io.mapsmessaging.state.adapter.kelluu;

import io.mapsmessaging.MessageDaemon;
import io.mapsmessaging.api.MessageEvent;
import io.mapsmessaging.api.MessageListener;
import io.mapsmessaging.api.Session;
import io.mapsmessaging.api.SessionContextBuilder;
import io.mapsmessaging.api.SessionManager;
import io.mapsmessaging.api.SubscriptionContextBuilder;
import io.mapsmessaging.api.features.ClientAcknowledgement;
import io.mapsmessaging.api.features.QualityOfService;
import io.mapsmessaging.engine.session.ClientConnection;
import io.mapsmessaging.state.adapter.StateMessageAdapter;
import io.mapsmessaging.state.drone.core.EntityTwin;
import io.mapsmessaging.state.drone.core.TwinManager;
import io.mapsmessaging.state.drone.core.TwinUpdateContext;
import io.mapsmessaging.state.drone.drone.DroneTwin;
import io.mapsmessaging.state.metrics.FeedActivityRegistry;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.security.Principal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.LongAdder;

/**
 * Consumes the Kelluu airship feed ({@code /feed/kelluu/positions} and {@code /feed/kelluu/targets})
 * and registers or updates one twin per airship and per detected target via
 * {@link KelluuMessageMapper}. From there the existing twin pipeline takes over: TAK output, KPIs
 * and STANAG node state. Subscribes through MAPS' internal session API, like the CoT ingest adapter.
 */
public class KelluuIngestAdapter implements StateMessageAdapter, ClientConnection, MessageListener {

  static final String UPDATE_SOURCE = "kelluu-ingest";
  /** Feed name prefix in {@link FeedActivityRegistry}: one feed per Kelluu platform. */
  public static final String FEED_PREFIX = "kelluu:";

  private final Logger logger = LoggerFactory.getLogger(KelluuIngestAdapter.class);
  private final KelluuMessageMapper mapper = new KelluuMessageMapper();
  private final String topic;
  private final TwinManager twinManager;

  private final LongAdder routedCount = new LongAdder();
  private final LongAdder droppedCount = new LongAdder();

  private Session session;

  public KelluuIngestAdapter(String topic, TwinManager twinManager) {
    this.topic = topic;
    this.twinManager = twinManager;
  }

  @Override
  public String getName() {
    return "kelluu-ingest";
  }

  @Override
  public void start() {
    try {
      SessionContextBuilder sessionContextBuilder = new SessionContextBuilder(sessionId(), this);
      sessionContextBuilder.setUsername("anonymous")
          .setPassword("".toCharArray())
          .isInternal(true)
          .setPersistentSession(false)
          .setSessionExpiry(0)
          .setReceiveMaximum(100);
      session = SessionManager.getInstance().create(sessionContextBuilder.build(), this);
      session.addSubscription(new SubscriptionContextBuilder(topic, ClientAcknowledgement.AUTO)
          .setQos(QualityOfService.AT_LEAST_ONCE)
          .build());
      logger.info("Kelluu ingest adapter subscribed to {}", topic);
    } catch (Throwable t) {
      closeSessionQuietly();
      // Deliberately broad, as in CotIngestAdapter: an uncaught Throwable here would stop the whole
      // state subsystem, not just this feed.
      logger.error("Kelluu ingest adapter failed to start on topic {} - Kelluu data will not be routed", topic, t);
    }
  }

  @Override
  public void stop() {
    closeSessionQuietly();
  }

  private void closeSessionQuietly() {
    Session current = session;
    session = null;
    if (current != null) {
      try {
        SessionManager.getInstance().close(current, false);
      } catch (IOException e) {
        logger.warn("Kelluu ingest adapter failed to close its session cleanly", e);
      }
    }
  }

  @Override
  public void sendMessage(@NotNull MessageEvent messageEvent) {
    try {
      handle(messageEvent.getDestinationName(), messageEvent.getMessage().getOpaqueData());
    } catch (Exception e) {
      droppedCount.increment();
      logger.warn("Kelluu ingest adapter failed to process an incoming message, dropped", e);
    } finally {
      if (messageEvent.getCompletionTask() != null) {
        messageEvent.getCompletionTask().run();
      }
    }
  }

  /** @return true when the message updated or created a twin. */
  boolean handle(String destinationName, byte[] payload) {
    Optional<DroneTwin> mapped = mapper.map(payload);
    if (mapped.isEmpty()) {
      droppedCount.increment();
      logger.debug("Kelluu message on {} is not a usable position or target, dropped", destinationName);
      return false;
    }
    DroneTwin fresh = mapped.get();
    String platformId = fresh.getAttributes().getOrDefault(KelluuMessageMapper.PLATFORM_ATTRIBUTE, "unknown");
    FeedActivityRegistry.recordActivity(FEED_PREFIX + platformId);

    TwinUpdateContext context = new TwinUpdateContext();
    context.setUpdateSource(UPDATE_SOURCE);
    context.setSourceInstanceId(platformId);
    context.setSourceNamespace(destinationName);
    context.setReceivedTime(Instant.now());

    if (twinManager.getTwin(fresh.getTwinId()).isPresent()) {
      twinManager.updateTwin(fresh.getTwinId(), existing -> copyOnto(existing, fresh), context);
    } else {
      twinManager.registerTwin(fresh, context);
    }
    routedCount.increment();
    return true;
  }

  static void copyOnto(EntityTwin existing, DroneTwin fresh) {
    existing.setGeoPosition(fresh.getGeoPosition());
    if (fresh.getDisplayName() != null) {
      existing.setDisplayName(fresh.getDisplayName());
    }
    if (existing instanceof DroneTwin existingDrone) {
      if (fresh.getCallSign() != null) {
        existingDrone.setCallSign(fresh.getCallSign());
      }
      if (fresh.getHeadingDegrees() != null) {
        existingDrone.setHeadingDegrees(fresh.getHeadingDegrees());
      }
      if (fresh.getCourseOverGroundDegrees() != null) {
        existingDrone.setCourseOverGroundDegrees(fresh.getCourseOverGroundDegrees());
      }
    }
    for (Map.Entry<String, String> attribute : fresh.getAttributes().entrySet()) {
      existing.getAttributes().put(attribute.getKey(), attribute.getValue());
    }
  }

  String sessionId() {
    return "kelluu-ingest-adapter:" + MessageDaemon.getInstance().getId();
  }

  public long getRoutedCount() {
    return routedCount.sum();
  }

  public long getDroppedCount() {
    return droppedCount.sum();
  }

  // --- ClientConnection: no network endpoint of its own, it rides an internal session. ---

  @Override
  public long getTimeOut() {
    return 0;
  }

  @Override
  public String getVersion() {
    return "1.0";
  }

  @Override
  public void sendKeepAlive() {
  }

  @Override
  public Principal getPrincipal() {
    return null;
  }

  @Override
  public String getAuthenticationConfig() {
    return "";
  }

  @Override
  public String getUniqueName() {
    return "kelluu-ingest-adapter";
  }

  @Override
  public String getProtocolName() {
    return "internal";
  }

  @Override
  public String getRemoteIp() {
    return "";
  }
}
