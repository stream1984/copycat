/*
 * Copyright 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.atomix.copycat.client.session;

import io.atomix.catalyst.util.Listener;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Provides event-based methods for monitoring Raft sessions and communicating between Raft clients and servers.
 * <p>
 * Each client that connects to a Raft cluster must open a {@link Session} in order to submit operations to the cluster.
 * When a client first connects to a server, it must register a new session. Once the session has been registered,
 * it can be used to submit {@link io.atomix.copycat.client.Command commands} and {@link io.atomix.copycat.client.Query queries}
 * or {@link #publish(String, Object) publish} and {@link #onEvent(String, Consumer) receive} session events.
 * <p>
 * Sessions represent a connection between a single client and all servers in a Raft cluster. Session information
 * is replicated via the Raft consensus algorithm, and clients can safely switch connections between servers without
 * losing their session. All consistency guarantees are provided within the context of a session. Once a session is
 * expired or closed, linearizability, sequential consistency, and other guarantees for events and operations are
 * effectively lost. Session implementations guarantee linearizability for session messages by coordinating between
 * the client and a single server at any given time. This means messages {@link #publish(String, Object) published}
 * via the {@link Session} are guaranteed to arrive on the other side of the connection exactly once and in the order
 * in which they are sent by replicated state machines. In the event of a server-to-client message being lost, the
 * message will be resent so long as at least one Raft server is able to communicate with the client and the client's
 * session does not expire while switching between servers.
 * <p>
 * Messages are sent to the other side of the session using the {@link #publish(String, Object)} method:
 * <pre>
 *   {@code
 *     session.publish("myEvent", "Hello world!");
 *   }
 * </pre>
 * When the message is published, it will be queued to be sent to the other side of the connection. Copycat guarantees
 * that the message will arrive within the session timeout unless the session itself times out.
 * <p>
 * To listen for events on a session register a {@link Consumer} via {@link #onEvent(String, Consumer)}:
 * <pre>
 *   {@code
 *     session.onEvent("myEvent", message -> System.out.println("Received: " + message));
 *   }
 * </pre>
 * Messages will always be received in the same thread and in the order in which they were sent by the other side of
 * the connection. Note that messages can be sent and received from the client or server side of the connection.
 *
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
public interface Session {

  /**
   * Represents the state of a session.
   * <p>
   * Throughout the lifetime of a session, the session may change state as a result of communication or the lack
   * thereof between the client and the cluster. See the specific states for more documentation.
   */
  enum State {

    /**
     * Indicates that the session is connected and open.
     * <p>
     * This is the initial state of a session upon registration. Once the session is registered with the cluster,
     * the session's state will be {@code OPEN} and will remain open so long as the client is able to communicate
     * with the cluster to keep its session alive.
     * <p>
     * Clients withe a session in the {@code OPEN} state can be assumed to be operating normally and are guaranteed
     * to benefit from linearizable reads and writes.
     */
    OPEN,

    /**
     * Indicates that the session in an unstable state and may or may not be {@link #EXPIRED}.
     * <p>
     * The unstable state is indicative of a state in which a client is unable to communicate with the cluster and
     * therefore cannot determine whether its session is or is not expired. Until the client is able to re-establish
     * communication with the cluster, its session will remain in this state. While in the {@code UNSTABLE} state,
     * users of the client should assume that the session may have been expired and that other clients may react
     * to the client having been expired while in the unstable state.
     * <p>
     * Commands submitted or completed in the {@code UNSTABLE} state may not be linearizable. An unstable session
     * may be expired and a client may have to resubmit associated commands once a new session in registered. This
     * can lead to duplicate commands being applied to servers state machines, thus breaking linearizability.
     * <p>
     * Once the client is able to re-establish communication with the cluster again it will determine whether the
     * session is still active or indeed has been expired. The session will either transition back to the
     * {@link #OPEN} state or {@link #EXPIRED} based on feedback from the cluster. Only the cluster can explicitly
     * expire a session.
     */
    UNSTABLE,

    /**
     * Indicates that the session is expired.
     * <p>
     * Once an {@link #UNSTABLE} client re-establishes communication with the cluster, the cluster may indicate to
     * the client that its session has been expired. In that case, the client may no longer submit operations under
     * the expired session and must register a new session to continue operating on server state machines.
     * <p>
     * When a client's session is expired, commands submitted under the expired session that have not yet been completed
     * may or may not be applied to server state machines. Linearizability is guaranteed only within the context of a
     * session, and so linearizability may be broken if operations are resubmitted across sessions.
     */
    EXPIRED,

    /**
     * Indicates that the session has been closed.
     * <p>
     * This state indicates that the client's session was explicitly unregistered and the session was closed safely.
     */
    CLOSED,

  }

  /**
   * Returns the session ID.
   * <p>
   * The session ID is unique to an individual session within the cluster. That is, it is guaranteed that
   * no two clients will have a session with the same ID.
   *
   * @return The session ID.
   */
  long id();

  /**
   * Returns the current session state.
   *
   * @return The current session state.
   */
  State state();

  /**
   * Registers a callback to be called when the session state changes.
   *
   * @param callback The callback to be called when the session state changes.
   * @return The state change listener.
   */
  Listener<State> onStateChange(Consumer<State> callback);

  /**
   * Publishes a {@code null} named event to the session.
   * <p>
   * When an event is published via the {@link Session}, it is sent to the other side of the session's
   * connection. Events can only be sent from a server-side replicated state machine to a client. Attempts
   * to send events from the client-side of the session will result in the event being handled by the client,
   * Sessions guarantee serializable consistency. If an event is sent from a Raft server to a client that is
   * disconnected or otherwise can't receive the event, the event will be resent once the client connects to
   * another server as long as its session has not expired.
   * <p>
   * Event messages must be serializable. For fast serialization, message types should implement
   * {@link io.atomix.catalyst.serializer.CatalystSerializable} or register a custom
   * {@link io.atomix.catalyst.serializer.TypeSerializer}. Normal Java {@link java.io.Serializable} and
   * {@link java.io.Externalizable} are supported but not recommended.
   * <p>
   * The returned {@link CompletableFuture} will be completed once the {@code event} has been sent
   * but not necessarily received by the other side of the connection. In the event of a network or other
   * failure, the message may be resent.
   *
   * @param event The event to publish.
   * @return A completable future to be called once the event has been published.
   * @throws NullPointerException If {@code event} is {@code null}
   * @throws ClosedSessionException If the session is closed
   * @throws io.atomix.catalyst.serializer.SerializationException If {@code message} cannot be serialized
   */
  Session publish(String event);

  /**
   * Publishes an event to the session.
   * <p>
   * When an event is published via the {@link Session}, it is sent to the other side of the session's
   * connection. Events can only be sent from a server-side replicated state machine to a client. Attempts
   * to send events from the client-side of the session will result in the event being handled by the client,
   * Sessions guarantee serializable consistency. If an event is sent from a Raft server to a client that is
   * disconnected or otherwise can't receive the event, the event will be resent once the client connects to
   * another server as long as its session has not expired.
   * <p>
   * Event messages must be serializable. For fast serialization, message types should implement
   * {@link io.atomix.catalyst.serializer.CatalystSerializable} or register a custom
   * {@link io.atomix.catalyst.serializer.TypeSerializer}. Normal Java {@link java.io.Serializable} and
   * {@link java.io.Externalizable} are supported but not recommended.
   * <p>
   * The returned {@link CompletableFuture} will be completed once the {@code event} has been sent
   * but not necessarily received by the other side of the connection. In the event of a network or other
   * failure, the message may be resent.
   *
   * @param event The event to publish.
   * @param message The event message. The message must be serializable either by implementing
   *               {@link io.atomix.catalyst.serializer.CatalystSerializable}, providing a
   *               {@link io.atomix.catalyst.serializer.TypeSerializer}, or implementing {@link java.io.Serializable}.
   * @return A completable future to be called once the event has been published.
   * @throws NullPointerException If {@code event} is {@code null}
   * @throws ClosedSessionException If the session is closed
   * @throws io.atomix.catalyst.serializer.SerializationException If {@code message} cannot be serialized
   */
  Session publish(String event, Object message);

  /**
   * Registers a void event listener.
   * <p>
   * The registered {@link Runnable} will be {@link Runnable#run() called} when an event is received
   * from the Raft cluster for the session. {@link Session} implementations must guarantee that consumers are
   * always called in the same thread for the session. Therefore, no two events will be received concurrently
   * by the session. Additionally, events are guaranteed to be received in the order in which they were sent by
   * the state machine.
   *
   * @param event The event to which to listen.
   * @param callback The session receive callback.
   * @return The listener context.
   * @throws NullPointerException if {@code event} or {@code callback} is null
   */
  Listener<Void> onEvent(String event, Runnable callback);

  /**
   * Registers an event listener.
   * <p>
   * The registered {@link Consumer} will be {@link Consumer#accept(Object) called} when an event is received
   * from the Raft cluster for the session. {@link Session} implementations must guarantee that consumers are
   * always called in the same thread for the session. Therefore, no two events will be received concurrently
   * by the session. Additionally, events are guaranteed to be received in the order in which they were sent by
   * the state machine.
   *
   * @param event The event to which to listen.
   * @param callback The session receive callback.
   * @param <T> The session event type.
   * @return The listener context.
   * @throws NullPointerException if {@code event} or {@code callback} is null
   */
  <T> Listener<T> onEvent(String event, Consumer<T> callback);

}
