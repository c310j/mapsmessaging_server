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
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.LongAdder;

/**
 * Consumes the Kelluu airship feed ({@code /feed/kelluu/positions} and {@code /feed/kelluu/targets})
 * and registers or updates one twin per airship and per detected target via
 * {@link KelluuMessageMapper}. From there the existing twin pipeline takes over: TAK output, KPIs
 * and STANAG node state. Subscribes through MAPS' internal session API, like the CoT ingest adapter.
 *
 * <p>{@link #sendMessage} runs on the engine's message-delivery thread and only queues the payload.
 * A dedicated worker thread updates the twins: a twin update stores messages on other destinations
 * and waits for them, and doing that on the delivery thread stalled the whole engine on central
 * (2026-10-07, the same task-pool deadlock {@code EventPublisher} avoids the same way).
 */
public class KelluuIngestAdapter implements StateMessageAdapter, ClientConnection, MessageListener {

  static final String UPDATE_SOURCE = "kelluu-ingest";
  /** Feed name prefix in {@link FeedActivityRegistry}: one feed per Kelluu platform. */
  public static final String FEED_PREFIX = "kelluu:";

  static final int MAX_QUEUE_SIZE = 1000;
  private static final long DROP_LOG_INTERVAL = 1000;

  private final Logger logger = LoggerFactory.getLogger(KelluuIngestAdapter.class);
  private final KelluuMessageMapper mapper = new KelluuMessageMapper();
  private final String topic;
  private final TwinManager twinManager;
  private final LinkedBlockingDeque<PendingMessage> queue = new LinkedBlockingDeque<>(MAX_QUEUE_SIZE);

  private final LongAdder routedCount = new LongAdder();
  private final LongAdder droppedCount = new LongAdder();
  private final LongAdder queueFullCount = new LongAdder();

  private Session session;
  private Thread worker;
  private volatile boolean running;

  private record PendingMessage(String destinationName, byte[] payload) {
  }

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
    startWorker();
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
    stopWorker();
  }

  synchronized void startWorker() {
    if (worker != null) {
      return;
    }
    running = true;
    worker = new Thread(this::workerLoop, "kelluu-ingest-worker");
    worker.setDaemon(true);
    worker.start();
  }

  synchronized void stopWorker() {
    running = false;
    if (worker != null) {
      worker.interrupt();
      worker = null;
    }
  }

  private void workerLoop() {
    while (running) {
      PendingMessage pending;
      try {
        pending = queue.takeFirst();
      } catch (InterruptedException interruptedException) {
        Thread.currentThread().interrupt();
        return;
      }
      try {
        handle(pending.destinationName(), pending.payload());
      } catch (Exception e) {
        droppedCount.increment();
        logger.warn("Kelluu ingest adapter failed to process an incoming message, dropped", e);
      }
    }
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

  /** Runs on the engine's delivery thread: queue the payload and return, never touch twins here. */
  @Override
  public void sendMessage(@NotNull MessageEvent messageEvent) {
    try {
      enqueue(messageEvent.getDestinationName(), messageEvent.getMessage().getOpaqueData());
    } finally {
      if (messageEvent.getCompletionTask() != null) {
        messageEvent.getCompletionTask().run();
      }
    }
  }

  /** Queues a message for the worker without blocking; when the queue is full the oldest is dropped. */
  void enqueue(String destinationName, byte[] payload) {
    PendingMessage pending = new PendingMessage(destinationName, payload == null ? null : payload.clone());
    boolean dropped = false;
    synchronized (queue) {
      if (queue.remainingCapacity() == 0) {
        queue.pollFirst();
        dropped = true;
      }
      queue.offerLast(pending);
    }
    if (dropped) {
      queueFullCount.increment();
      long total = queueFullCount.sum();
      // Rate-limited: a stalled twin pipeline at feed rate would otherwise flood the log.
      if (total == 1 || total % DROP_LOG_INTERVAL == 0) {
        logger.warn("Kelluu ingest queue full ({} messages), dropped the oldest; {} dropped so far", MAX_QUEUE_SIZE, total);
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

  public long getQueueFullDropCount() {
    return queueFullCount.sum();
  }

  int getQueueSize() {
    return queue.size();
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
