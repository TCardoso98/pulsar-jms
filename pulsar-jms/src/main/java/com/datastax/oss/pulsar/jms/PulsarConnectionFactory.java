/*
 * Copyright DataStax, Inc.
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
package com.datastax.oss.pulsar.jms;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import javax.jms.ConnectionFactory;
import javax.jms.IllegalStateException;
import javax.jms.InvalidClientIDException;
import javax.jms.JMSContext;
import javax.jms.JMSException;
import javax.jms.JMSRuntimeException;
import javax.jms.JMSSecurityException;
import javax.jms.JMSSecurityRuntimeException;
import javax.jms.QueueConnection;
import javax.jms.QueueConnectionFactory;
import javax.jms.TopicConnection;
import javax.jms.TopicConnectionFactory;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.admin.PulsarAdminException;
import org.apache.pulsar.client.api.Authentication;
import org.apache.pulsar.client.api.AuthenticationFactory;
import org.apache.pulsar.client.api.ClientBuilder;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.ConsumerBuilder;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.MessageId;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.ProducerBuilder;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.Reader;
import org.apache.pulsar.client.api.ReaderBuilder;
import org.apache.pulsar.client.api.SubscriptionInitialPosition;
import org.apache.pulsar.client.api.SubscriptionMode;
import org.apache.pulsar.client.api.SubscriptionType;

@Slf4j
public class PulsarConnectionFactory
    implements ConnectionFactory, QueueConnectionFactory, TopicConnectionFactory, AutoCloseable {
  private static final Set<String> clientIdentifiers = new ConcurrentSkipListSet<>();

  private final Map<String, Producer<byte[]>> producers = new ConcurrentHashMap<>();
  private final Set<PulsarConnection> connections = Collections.synchronizedSet(new HashSet<>());
  private final List<Consumer<byte[]>> consumers = new CopyOnWriteArrayList<>();
  private final List<Reader<byte[]>> readers = new CopyOnWriteArrayList<>();
  private PulsarClient pulsarClient;
  private PulsarAdmin pulsarAdmin;
  private Map<String, Object> producerConfiguration;
  private Map<String, Object> consumerConfiguration;
  private String systemNamespace = "public/default";
  private String defaultClientId = null;
  private boolean enableTransaction = false;
  private boolean enableClientSideFeatures = false;
  private boolean forceDeleteTemporaryDestinations = false;
  private boolean useExclusiveSubscriptionsForSimpleConsumers = false;
  private String tckUsername = null;
  private String tckPassword = null;
  private String queueSubscriptionName = "jms-queue";
  private long waitForServerStartupTimeout = 60000;
  private boolean initialized;

  private Map<String, Object> configuration;

  public PulsarConnectionFactory() throws JMSException {
    this(new HashMap<>());
  }

  public PulsarConnectionFactory(Map<String, Object> properties) throws JMSException {
    this.configuration = new HashMap(properties);
  }

  /**
   * Utility method for configuration based on JSON
   *
   * @return JSON encoded configuration
   */
  public String getJsonConfiguration() {
    return Utils.runtimeException(() -> new ObjectMapper().writeValueAsString(getConfiguration()));
  }

  /**
   * Apply configuration from a JSON encoded string
   *
   * @param json the JSON
   */
  public void setJsonConfiguration(String json) {
    if (json == null || json.isEmpty()) {
      setConfiguration(Collections.emptyMap());
      return;
    }
    setConfiguration(Utils.runtimeException(() -> new ObjectMapper().readValue(json, Map.class)));
  }

  public synchronized Map<String, Object> getConfiguration() {
    return new HashMap<>(configuration);
  }

  public synchronized void setConfiguration(Map<String, Object> configuration) {
    this.configuration = new HashMap<>(configuration);
  }

  private synchronized Map<String, Object> getConsumerConfiguration() {
    return consumerConfiguration;
  }

  private synchronized Map<String, Object> getProducerConfiguration() {
    return producerConfiguration;
  }

  private synchronized void ensureInitialized() throws JMSException {
    if (initialized) {
      return;
    }
    try {

      Map<String, Object> producerConfiguration =
          (Map<String, Object>) configuration.remove("producerConfig");
      if (producerConfiguration != null) {
        this.producerConfiguration = new HashMap(producerConfiguration);
      } else {
        this.producerConfiguration = Collections.emptyMap();
      }

      Map<String, Object> consumerConfigurationM =
          (Map<String, Object>) configuration.remove("consumerConfig");
      if (consumerConfigurationM != null) {
        this.consumerConfiguration = new HashMap(consumerConfigurationM);
      } else {
        this.consumerConfiguration = Collections.emptyMap();
      }
      this.systemNamespace =
          getAndRemoveString("jms.systemNamespace", "public/default", configuration);

      this.tckUsername = getAndRemoveString("jms.tckUsername", "", configuration);
      this.tckPassword = getAndRemoveString("jms.tckPassword", "", configuration);

      this.defaultClientId = getAndRemoveString("jms.clientId", null, configuration);

      this.queueSubscriptionName = getAndRemoveString("jms.queueName", "jms-queue", configuration);

      this.waitForServerStartupTimeout =
          Long.parseLong(
              getAndRemoveString("jms.waitForServerStartupTimeout", "60000", configuration));

      this.enableClientSideFeatures =
          Boolean.parseBoolean(
              getAndRemoveString("jms.enableClientSideFeatures", "false", configuration));

      // in Exclusive mode Pulsar does not support delayed messages
      // with this flag you force to not use Exclusive subscription and so to support
      // delayed messages are well
      this.useExclusiveSubscriptionsForSimpleConsumers =
          Boolean.parseBoolean(
              getAndRemoveString(
                  "jms.useExclusiveSubscriptionsForSimpleConsumers", "true", configuration));

      // default is false
      this.forceDeleteTemporaryDestinations =
          Boolean.parseBoolean(
              getAndRemoveString("jms.forceDeleteTemporaryDestinations", "false", configuration));

      this.enableTransaction =
          Boolean.parseBoolean(configuration.getOrDefault("enableTransaction", "false").toString());

      String webServiceUrl =
          getAndRemoveString("webServiceUrl", "http://localhost:8080", configuration);

      String brokenServiceUrl = getAndRemoveString("brokerServiceUrl", "", configuration);

      PulsarClient pulsarClient = null;
      PulsarAdmin pulsarAdmin = null;
      try {

        // must be the same as
        // https://pulsar.apache.org/docs/en/security-tls-keystore/#configuring-clients
        String authPluginClassName = getAndRemoveString("authPlugin", "", configuration);
        String authParamsString = getAndRemoveString("authParams", "", configuration);
        Authentication authentication =
            AuthenticationFactory.create(authPluginClassName, authParamsString);
        if (log.isDebugEnabled()) {
          log.debug("Authentication {}", authentication);
        }
        boolean tlsAllowInsecureConnection =
            Boolean.parseBoolean(
                getAndRemoveString("tlsAllowInsecureConnection", "false", configuration));

        boolean tlsEnableHostnameVerification =
            Boolean.parseBoolean(
                getAndRemoveString("tlsEnableHostnameVerification", "false", configuration));
        final String tlsTrustCertsFilePath =
            (String) getAndRemoveString("tlsTrustCertsFilePath", "", configuration);

        boolean useKeyStoreTls =
            Boolean.parseBoolean(getAndRemoveString("useKeyStoreTls", "false", configuration));
        String tlsTrustStoreType = getAndRemoveString("tlsTrustStoreType", "JKS", configuration);
        String tlsTrustStorePath = getAndRemoveString("tlsTrustStorePath", "", configuration);
        String tlsTrustStorePassword =
            getAndRemoveString("tlsTrustStorePassword", "", configuration);

        pulsarAdmin =
            PulsarAdmin.builder()
                .serviceHttpUrl(webServiceUrl)
                .allowTlsInsecureConnection(tlsAllowInsecureConnection)
                .enableTlsHostnameVerification(tlsEnableHostnameVerification)
                .tlsTrustCertsFilePath(tlsTrustCertsFilePath)
                .useKeyStoreTls(useKeyStoreTls)
                .tlsTrustStoreType(tlsTrustStoreType)
                .tlsTrustStorePath(tlsTrustStorePath)
                .tlsTrustStorePassword(tlsTrustStorePassword)
                .authentication(authentication)
                .build();

        ClientBuilder clientBuilder =
            PulsarClient.builder()
                .loadConf(configuration)
                .tlsTrustStorePassword(tlsTrustStorePassword)
                .tlsTrustStorePath(tlsTrustStorePath)
                .tlsTrustCertsFilePath(tlsTrustCertsFilePath)
                .tlsTrustStoreType(tlsTrustStoreType)
                .useKeyStoreTls(useKeyStoreTls)
                .enableTlsHostnameVerification(tlsEnableHostnameVerification)
                .allowTlsInsecureConnection(tlsAllowInsecureConnection)
                .serviceUrl(webServiceUrl)
                .authentication(authentication);
        if (!brokenServiceUrl.isEmpty()) {
          clientBuilder.serviceUrl(brokenServiceUrl);
        }

        pulsarClient = clientBuilder.build();

      } catch (PulsarClientException err) {
        if (pulsarAdmin != null) {
          pulsarAdmin.close();
        }
        if (pulsarClient != null) {
          pulsarClient.close();
        }
        throw err;
      }
      this.pulsarClient = pulsarClient;
      this.pulsarAdmin = pulsarAdmin;
      this.initialized = true;
    } catch (Throwable t) {
      throw Utils.handleException(t);
    }
  }

  private static String getAndRemoveString(
      String name, String defaultValue, Map<String, Object> properties) {
    Object value = (Object) properties.remove(name);
    return value != null ? value.toString() : defaultValue;
  }

  public synchronized boolean isEnableClientSideFeatures() {
    return enableClientSideFeatures;
  }

  synchronized String getDefaultClientId() {
    return defaultClientId;
  }

  public synchronized boolean isEnableTransaction() {
    return enableTransaction;
  }

  public synchronized PulsarClient getPulsarClient() {
    return pulsarClient;
  }

  public synchronized PulsarAdmin getPulsarAdmin() {
    return pulsarAdmin;
  }

  public synchronized String getSystemNamespace() {
    return systemNamespace;
  }

  /**
   * Creates a connection with the default user identity. The connection is created in stopped mode.
   * No messages will be delivered until the {@code Connection.start} method is explicitly called.
   *
   * @return a newly created connection
   * @throws JMSException if the JMS provider fails to create the connection due to some internal
   *     error.
   * @throws JMSSecurityException if client authentication fails due to an invalid user name or
   *     password.
   * @since JMS 1.1
   */
  @Override
  public PulsarConnection createConnection() throws JMSException {
    ensureInitialized();
    PulsarConnection res = new PulsarConnection(this);
    connections.add(res);
    return res;
  }

  /**
   * Creates a connection with the specified user identity. The connection is created in stopped
   * mode. No messages will be delivered until the {@code Connection.start} method is explicitly
   * called.
   *
   * @param userName the caller's user name
   * @param password the caller's password
   * @return a newly created connection
   * @throws JMSException if the JMS provider fails to create the connection due to some internal
   *     error.
   * @throws JMSSecurityException if client authentication fails due to an invalid user name or
   *     password.
   * @since JMS 1.1
   */
  @Override
  public PulsarConnection createConnection(String userName, String password) throws JMSException {
    ensureInitialized();
    validateDummyUserNamePassword(userName, password);
    return createConnection();
  }

  private synchronized void validateDummyUserNamePassword(String userName, String password)
      throws JMSSecurityException {
    if (!tckUsername.isEmpty() && !tckUsername.equals(userName) && !tckPassword.equals(password)) {
      // this verification is here only for the TCK, Pulsar does not support username/password
      // authentication
      // therefore we are using only one single PulsarClient per factory
      // authentication must be set at Factory level
      throw new JMSSecurityException("Unauthorized");
    }
  }

  /**
   * Creates a JMSContext with the default user identity and an unspecified sessionMode.
   *
   * <p>A connection and session are created for use by the new JMSContext. The connection is
   * created in stopped mode but will be automatically started when a JMSConsumer is created.
   *
   * <p>The behaviour of the session that is created depends on whether this method is called in a
   * Java SE environment, in the Java EE application client container, or in the Java EE web or EJB
   * container. If this method is called in the Java EE web or EJB container then the behaviour of
   * the session also depends on whether or not there is an active JTA transaction in progress.
   *
   * <p>In a <b>Java SE environment</b> or in <b>the Java EE application client container</b>:
   *
   * <ul>
   *   <li>The session will be non-transacted and received messages will be acknowledged
   *       automatically using an acknowledgement mode of {@code JMSContext.AUTO_ACKNOWLEDGE} For a
   *       definition of the meaning of this acknowledgement mode see the link below.
   * </ul>
   *
   * <p>In a <b>Java EE web or EJB container, when there is an active JTA transaction in
   * progress</b>:
   *
   * <ul>
   *   <li>The session will participate in the JTA transaction and will be committed or rolled back
   *       when that transaction is committed or rolled back, not by calling the {@code
   *       JMSContext}'s {@code commit} or {@code rollback} methods.
   * </ul>
   *
   * <p>In the <b>Java EE web or EJB container, when there is no active JTA transaction in
   * progress</b>:
   *
   * <ul>
   *   <li>The session will be non-transacted and received messages will be acknowledged
   *       automatically using an acknowledgement mode of {@code JMSContext.AUTO_ACKNOWLEDGE} For a
   *       definition of the meaning of this acknowledgement mode see the link below.
   * </ul>
   *
   * @return a newly created JMSContext
   * @throws JMSRuntimeException if the JMS provider fails to create the JMSContext due to some
   *     internal error.
   * @throws JMSSecurityRuntimeException if client authentication fails due to an invalid user name
   *     or password.
   * @see JMSContext#AUTO_ACKNOWLEDGE
   * @see ConnectionFactory#createContext(int)
   * @see ConnectionFactory#createContext(String, String)
   * @see ConnectionFactory#createContext(String, String, int)
   * @see JMSContext#createContext(int)
   * @since JMS 2.0
   */
  @Override
  public JMSContext createContext() {
    return createContext(JMSContext.AUTO_ACKNOWLEDGE);
  }

  /**
   * Creates a JMSContext with the specified user identity and an unspecified sessionMode.
   *
   * <p>A connection and session are created for use by the new JMSContext. The connection is
   * created in stopped mode but will be automatically started when a JMSConsumer.
   *
   * <p>The behaviour of the session that is created depends on whether this method is called in a
   * Java SE environment, in the Java EE application client container, or in the Java EE web or EJB
   * container. If this method is called in the Java EE web or EJB container then the behaviour of
   * the session also depends on whether or not there is an active JTA transaction in progress.
   *
   * <p>In a <b>Java SE environment</b> or in <b>the Java EE application client container</b>:
   *
   * <ul>
   *   <li>The session will be non-transacted and received messages will be acknowledged
   *       automatically using an acknowledgement mode of {@code JMSContext.AUTO_ACKNOWLEDGE} For a
   *       definition of the meaning of this acknowledgement mode see the link below.
   * </ul>
   *
   * <p>In a <b>Java EE web or EJB container, when there is an active JTA transaction in
   * progress</b>:
   *
   * <ul>
   *   <li>The session will participate in the JTA transaction and will be committed or rolled back
   *       when that transaction is committed or rolled back, not by calling the {@code
   *       JMSContext}'s {@code commit} or {@code rollback} methods.
   * </ul>
   *
   * <p>In the <b>Java EE web or EJB container, when there is no active JTA transaction in
   * progress</b>:
   *
   * <ul>
   *   <li>The session will be non-transacted and received messages will be acknowledged
   *       automatically using an acknowledgement mode of {@code JMSContext.AUTO_ACKNOWLEDGE} For a
   *       definition of the meaning of this acknowledgement mode see the link below.
   * </ul>
   *
   * @param userName the caller's user name
   * @param password the caller's password
   * @return a newly created JMSContext
   * @throws JMSRuntimeException if the JMS provider fails to create the JMSContext due to some
   *     internal error.
   * @throws JMSSecurityRuntimeException if client authentication fails due to an invalid user name
   *     or password.
   * @see JMSContext#AUTO_ACKNOWLEDGE
   * @see ConnectionFactory#createContext()
   * @see ConnectionFactory#createContext(int)
   * @see ConnectionFactory#createContext(String, String, int)
   * @see JMSContext#createContext(int)
   * @since JMS 2.0
   */
  @Override
  public JMSContext createContext(String userName, String password) {
    return createContext(userName, password, JMSContext.AUTO_ACKNOWLEDGE);
  }

  /**
   * Creates a JMSContext with the specified user identity and the specified session mode.
   *
   * <p>A connection and session are created for use by the new JMSContext. The JMSContext is
   * created in stopped mode but will be automatically started when a JMSConsumer is created.
   *
   * <p>The effect of setting the {@code sessionMode} argument depends on whether this method is
   * called in a Java SE environment, in the Java EE application client container, or in the Java EE
   * web or EJB container. If this method is called in the Java EE web or EJB container then the
   * effect of setting the {@code sessionMode} argument also depends on whether or not there is an
   * active JTA transaction in progress.
   *
   * <p>In a <b>Java SE environment</b> or in <b>the Java EE application client container</b>:
   *
   * <ul>
   *   <li>If {@code sessionMode} is set to {@code JMSContext.SESSION_TRANSACTED} then the session
   *       will use a local transaction which may subsequently be committed or rolled back by
   *       calling the {@code JMSContext}'s {@code commit} or {@code rollback} methods.
   *   <li>If {@code sessionMode} is set to any of {@code JMSContext.CLIENT_ACKNOWLEDGE}, {@code
   *       JMSContext.AUTO_ACKNOWLEDGE} or {@code JMSContext.DUPS_OK_ACKNOWLEDGE}. then the session
   *       will be non-transacted and messages received by this session will be acknowledged
   *       according to the value of {@code sessionMode}. For a definition of the meaning of these
   *       acknowledgement modes see the links below.
   * </ul>
   *
   * <p>In a <b>Java EE web or EJB container, when there is an active JTA transaction in
   * progress</b>:
   *
   * <ul>
   *   <li>The argument {@code sessionMode} is ignored. The session will participate in the JTA
   *       transaction and will be committed or rolled back when that transaction is committed or
   *       rolled back, not by calling the {@code JMSContext}'s {@code commit} or {@code rollback}
   *       methods. Since the argument is ignored, developers are recommended to use {@code
   *       createContext(String userName, String password)} instead of this method.
   * </ul>
   *
   * <p>In the <b>Java EE web or EJB container, when there is no active JTA transaction in
   * progress</b>:
   *
   * <ul>
   *   <li>The argument {@code acknowledgeMode} must be set to either of {@code
   *       JMSContext.AUTO_ACKNOWLEDGE} or {@code JMSContext.DUPS_OK_ACKNOWLEDGE}. The session will
   *       be non-transacted and messages received by this session will be acknowledged
   *       automatically according to the value of {@code acknowledgeMode}. For a definition of the
   *       meaning of these acknowledgement modes see the links below. The values {@code
   *       JMSContext.SESSION_TRANSACTED} and {@code JMSContext.CLIENT_ACKNOWLEDGE} may not be used.
   * </ul>
   *
   * @param userName the caller's user name
   * @param password the caller's password
   * @param sessionMode indicates which of four possible session modes will be used.
   *     <ul>
   *       <li>If this method is called in a Java SE environment or in the Java EE application
   *           client container, the permitted values are {@code JMSContext.SESSION_TRANSACTED},
   *           {@code JMSContext.CLIENT_ACKNOWLEDGE}, {@code JMSContext.AUTO_ACKNOWLEDGE} and {@code
   *           JMSContext.DUPS_OK_ACKNOWLEDGE}.
   *       <li>If this method is called in the Java EE web or EJB container when there is an active
   *           JTA transaction in progress then this argument is ignored.
   *       <li>If this method is called in the Java EE web or EJB container when there is no active
   *           JTA transaction in progress, the permitted values are {@code
   *           JMSContext.AUTO_ACKNOWLEDGE} and {@code JMSContext.DUPS_OK_ACKNOWLEDGE}. In this case
   *           the values {@code JMSContext.TRANSACTED} and {@code JMSContext.CLIENT_ACKNOWLEDGE}
   *           are not permitted.
   *     </ul>
   *
   * @return a newly created JMSContext
   * @throws JMSRuntimeException if the JMS provider fails to create the JMSContext due to some
   *     internal error.
   * @throws JMSSecurityRuntimeException if client authentication fails due to an invalid user name
   *     or password.
   * @see JMSContext#SESSION_TRANSACTED
   * @see JMSContext#CLIENT_ACKNOWLEDGE
   * @see JMSContext#AUTO_ACKNOWLEDGE
   * @see JMSContext#DUPS_OK_ACKNOWLEDGE
   * @see ConnectionFactory#createContext()
   * @see ConnectionFactory#createContext(int)
   * @see ConnectionFactory#createContext(String, String)
   * @see JMSContext#createContext(int)
   * @since JMS 2.0
   */
  @Override
  public JMSContext createContext(String userName, String password, int sessionMode) {
    Utils.runtimeException(() -> validateDummyUserNamePassword(userName, password));
    return createContext(sessionMode);
  }

  /**
   * Creates a JMSContext with the default user identity and the specified session mode.
   *
   * <p>A connection and session are created for use by the new JMSContext. The JMSContext is
   * created in stopped mode but will be automatically started when a JMSConsumer is created.
   *
   * <p>The effect of setting the {@code sessionMode} argument depends on whether this method is
   * called in a Java SE environment, in the Java EE application client container, or in the Java EE
   * web or EJB container. If this method is called in the Java EE web or EJB container then the
   * effect of setting the {@code sessionMode} argument also depends on whether or not there is an
   * active JTA transaction in progress.
   *
   * <p>In a <b>Java SE environment</b> or in <b>the Java EE application client container</b>:
   *
   * <ul>
   *   <li>If {@code sessionMode} is set to {@code JMSContext.SESSION_TRANSACTED} then the session
   *       will use a local transaction which may subsequently be committed or rolled back by
   *       calling the {@code JMSContext}'s {@code commit} or {@code rollback} methods.
   *   <li>If {@code sessionMode} is set to any of {@code JMSContext.CLIENT_ACKNOWLEDGE}, {@code
   *       JMSContext.AUTO_ACKNOWLEDGE} or {@code JMSContext.DUPS_OK_ACKNOWLEDGE}. then the session
   *       will be non-transacted and messages received by this session will be acknowledged
   *       according to the value of {@code sessionMode}. For a definition of the meaning of these
   *       acknowledgement modes see the links below.
   * </ul>
   *
   * <p>In a <b>Java EE web or EJB container, when there is an active JTA transaction in
   * progress</b>:
   *
   * <ul>
   *   <li>The argument {@code sessionMode} is ignored. The session will participate in the JTA
   *       transaction and will be committed or rolled back when that transaction is committed or
   *       rolled back, not by calling the {@code JMSContext}'s {@code commit} or {@code rollback}
   *       methods. Since the argument is ignored, developers are recommended to use {@code
   *       createContext()} instead of this method.
   * </ul>
   *
   * <p>In the <b>Java EE web or EJB container, when there is no active JTA transaction in
   * progress</b>:
   *
   * <ul>
   *   <li>The argument {@code acknowledgeMode} must be set to either of {@code
   *       JMSContext.AUTO_ACKNOWLEDGE} or {@code JMSContext.DUPS_OK_ACKNOWLEDGE}. The session will
   *       be non-transacted and messages received by this session will be acknowledged
   *       automatically according to the value of {@code acknowledgeMode}. For a definition of the
   *       meaning of these acknowledgement modes see the links below. The values {@code
   *       JMSContext.SESSION_TRANSACTED} and {@code JMSContext.CLIENT_ACKNOWLEDGE} may not be used.
   * </ul>
   *
   * @param sessionMode indicates which of four possible session modes will be used.
   *     <ul>
   *       <li>If this method is called in a Java SE environment or in the Java EE application
   *           client container, the permitted values are {@code JMSContext.SESSION_TRANSACTED},
   *           {@code JMSContext.CLIENT_ACKNOWLEDGE}, {@code JMSContext.AUTO_ACKNOWLEDGE} and {@code
   *           JMSContext.DUPS_OK_ACKNOWLEDGE}.
   *       <li>If this method is called in the Java EE web or EJB container when there is an active
   *           JTA transaction in progress then this argument is ignored.
   *       <li>If this method is called in the Java EE web or EJB container when there is no active
   *           JTA transaction in progress, the permitted values are {@code
   *           JMSContext.AUTO_ACKNOWLEDGE} and {@code JMSContext.DUPS_OK_ACKNOWLEDGE}. In this case
   *           the values {@code JMSContext.TRANSACTED} and {@code JMSContext.CLIENT_ACKNOWLEDGE}
   *           are not permitted.
   *     </ul>
   *
   * @return a newly created JMSContext
   * @throws JMSRuntimeException if the JMS provider fails to create the JMSContext due to some
   *     internal error.
   * @throws JMSSecurityRuntimeException if client authentication fails due to an invalid user name
   *     or password.
   * @see JMSContext#SESSION_TRANSACTED
   * @see JMSContext#CLIENT_ACKNOWLEDGE
   * @see JMSContext#AUTO_ACKNOWLEDGE
   * @see JMSContext#DUPS_OK_ACKNOWLEDGE
   * @see ConnectionFactory#createContext()
   * @see ConnectionFactory#createContext(String, String)
   * @see ConnectionFactory#createContext(String, String, int)
   * @see JMSContext#createContext(int)
   * @since JMS 2.0
   */
  @Override
  public JMSContext createContext(int sessionMode) {
    Utils.runtimeException(this::ensureInitialized);
    return new PulsarJMSContext(this, sessionMode);
  }

  public void close() {
    synchronized (this) {
      if (!initialized) {
        return;
      }
    }
    // close all connections and wait for ongoing operations
    // to complete
    for (PulsarConnection con : new ArrayList<>(connections)) {
      try {
        con.close();
      } catch (Exception ignore) {
        // ignore
        Utils.handleException(ignore);
      }
    }

    for (Producer<?> producer : producers.values()) {
      try {
        producer.close();
      } catch (PulsarClientException ignore) {
        // ignore
        Utils.handleException(ignore);
      }
    }

    this.pulsarAdmin.close();

    try {
      this.pulsarClient.close();
    } catch (PulsarClientException err) {
      log.info("Error closing PulsarClient", err);
    }
  }

  Producer<byte[]> getProducerForDestination(
      PulsarDestination defaultDestination, boolean transactions) throws JMSException {
    try {
      return producers.computeIfAbsent(
          defaultDestination.topicName + "-" + transactions,
          d -> {
            try {
              return Utils.invoke(
                  () -> {
                    ProducerBuilder<byte[]> producerBuilder =
                        pulsarClient
                            .newProducer()
                            .topic(defaultDestination.topicName)
                            .loadConf(getProducerConfiguration());
                    if (transactions) {
                      // this is a limitation of Pulsar transaction support
                      producerBuilder.sendTimeout(0, TimeUnit.MILLISECONDS);
                    }
                    return producerBuilder.create();
                  });
            } catch (JMSException err) {
              throw new RuntimeException(err);
            }
          });
    } catch (RuntimeException err) {
      throw (JMSException) err.getCause();
    }
  }

  public void ensureQueueSubscription(PulsarDestination destination) throws JMSException {
    long start = System.currentTimeMillis();
    while (true) {
      try {
        getPulsarAdmin()
            .topics()
            .createSubscription(
                destination.topicName, getQueueSubscriptionName(), MessageId.earliest);
        break;
      } catch (PulsarAdminException.ConflictException exists) {
        log.debug(
            "Subscription {} already exists for {}",
            getQueueSubscriptionName(),
            destination.topicName);
        break;
      } catch (PulsarAdminException err) {
        // special handling for server startup
        // it mitigates problems in tests
        // but also it is useful in order to let
        // applications start when the server is not available
        long now = System.currentTimeMillis();
        if (now - start > getWaitForServerStartupTimeout()) {
          throw Utils.handleException(err);
        } else {
          log.info(
              "Got {} error while setting up subscription for queue {}, maybe the namespace/broker is still starting",
              err.toString(),
              destination);

          try {
            Thread.sleep(1000);
          } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw Utils.handleException(err);
          }
        }
      }
    }
  }

  public void ensureSubscription(PulsarDestination destination, String consumerName)
      throws JMSException {
    // for queues we have a single shared subscription
    String subscriptionName = destination.isQueue() ? queueSubscriptionName : consumerName;
    log.info("Creating subscription {} for destination {}", subscriptionName, destination);
    try {
      pulsarAdmin
          .topics()
          .createSubscription(destination.topicName, subscriptionName, MessageId.latest);
    } catch (PulsarAdminException.ConflictException alreadyExists) {
      log.info("Subscription {} already exists, this is usually not a problem", subscriptionName);
    } catch (Exception err) {
      throw Utils.handleException(err);
    }
  }

  public Consumer<byte[]> createConsumer(
      PulsarDestination destination,
      String consumerName,
      int sessionMode,
      SubscriptionMode subscriptionMode,
      SubscriptionType subscriptionType)
      throws JMSException {
    // for queues we have a single shared subscription
    String subscriptionName = destination.isQueue() ? queueSubscriptionName : consumerName;
    SubscriptionInitialPosition initialPosition =
        destination.isTopic()
            ? SubscriptionInitialPosition.Latest
            : SubscriptionInitialPosition.Earliest;
    MessageId seekMessageId = null;
    if (destination.isQueue() && subscriptionMode != SubscriptionMode.Durable) {
      throw new IllegalStateException("only durable mode for queues");
    }
    if (destination.isQueue() && subscriptionType != SubscriptionType.Shared) {
      throw new IllegalStateException("only Shared SubscriptionType for queues");
    }

    log.debug(
        "createConsumer {} {} {}",
        destination.topicName,
        consumerName,
        subscriptionMode,
        subscriptionType);

    try {
      ConsumerBuilder<byte[]> builder =
          pulsarClient
              .newConsumer()
              // these properties can be overridden by the configuration
              .negativeAckRedeliveryDelay(1, TimeUnit.SECONDS)
              .loadConf(getConsumerConfiguration())
              // these properties cannot be overwritten by the configuration
              .subscriptionInitialPosition(initialPosition)
              .subscriptionMode(subscriptionMode)
              .subscriptionType(subscriptionType)
              .subscriptionName(subscriptionName)
              .topic(destination.topicName);
      Consumer<byte[]> newConsumer = builder.subscribe();
      consumers.add(newConsumer);
      return newConsumer;
    } catch (PulsarClientException err) {
      throw Utils.handleException(err);
    }
  }

  public Reader<byte[]> createReaderForBrowser(PulsarQueue destination) throws JMSException {

    try {
      List<Message<byte[]>> messages =
          getPulsarAdmin().topics().peekMessages(destination.topicName, queueSubscriptionName, 1);

      MessageId seekMessageId;
      if (messages.isEmpty()) {
        // no more messages
        seekMessageId = MessageId.latest;
      } else {
        seekMessageId = messages.get(0).getMessageId();
      }
      ;
      log.info("createBrowser {} at {}", destination.topicName, seekMessageId);

      ReaderBuilder<byte[]> builder =
          pulsarClient
              .newReader()
              // these properties can be overridden by the configuration
              .loadConf(getConsumerConfiguration())
              // these properties cannot be overwritten by the configuration
              .readerName("jms-queue-browser-" + UUID.randomUUID())
              .startMessageId(seekMessageId)
              .startMessageIdInclusive()
              .topic(destination.topicName);
      Reader<byte[]> newReader = builder.create();
      readers.add(newReader);
      return newReader;
    } catch (PulsarClientException | PulsarAdminException err) {
      throw Utils.handleException(err);
    }
  }

  public void removeConsumer(Consumer<byte[]> consumer) {
    consumers.remove(consumer);
  }

  public void removeReader(Reader<byte[]> reader) {
    readers.remove(reader);
  }

  public boolean deleteSubscription(PulsarDestination destination, String name)
      throws JMSException {
    String systemNamespace = getSystemNamespace();
    boolean somethingDone = false;
    try {

      if (destination != null) {
        log.info("deleteSubscription topic {} name {}", destination.topicName, name);
        pulsarAdmin.topics().deleteSubscription(destination.topicName, name, true);
        somethingDone = true;
      }

      if (!somethingDone) {
        // required for TCK, scan for all subscriptions
        List<String> allTopics = pulsarAdmin.topics().getList(systemNamespace);
        for (String topic : allTopics) {
          log.info("Scanning topic {}", topic);
          List<String> subscriptions = pulsarAdmin.topics().getSubscriptions(topic);
          log.info("Subscriptions {}", subscriptions);
          for (String subscription : subscriptions) {
            log.info("Found subscription {} ", subscription);
            if (subscription.equals(name)) {
              log.info("deleteSubscription topic {} name {}", topic, name);
              pulsarAdmin.topics().deleteSubscription(topic, name, true);
              somethingDone = true;
            }
          }
        }
      }
    } catch (PulsarAdminException.NotFoundException notFound) {
      log.error("Cannot unsubscribe {} from {}: not found", name, destination.topicName);
    } catch (Exception err) {
      throw Utils.handleException(err);
    }
    return somethingDone;
  }

  public void registerClientId(String clientID) throws InvalidClientIDException {
    log.info("registerClientId {}, existing {}", clientID, clientIdentifiers);
    if (!clientIdentifiers.add(clientID)) {
      throw new InvalidClientIDException(
          "A connection with this client id '" + clientID + "'is already opened locally");
    }
  }

  public void unregisterConnection(PulsarConnection connection) {
    if (connection.clientId != null) {
      clientIdentifiers.remove(connection.clientId);
      log.info("unregisterClientId {} {}", connection.clientId, clientIdentifiers);
    }
    connections.remove(connection);
  }

  @Override
  public QueueConnection createQueueConnection() throws JMSException {
    return createConnection();
  }

  @Override
  public QueueConnection createQueueConnection(String s, String s1) throws JMSException {
    return createConnection(s, s1);
  }

  @Override
  public TopicConnection createTopicConnection() throws JMSException {
    return createConnection();
  }

  @Override
  public TopicConnection createTopicConnection(String s, String s1) throws JMSException {
    return createConnection(s, s1);
  }

  public synchronized boolean isForceDeleteTemporaryDestinations() {
    return forceDeleteTemporaryDestinations;
  }

  public synchronized String getQueueSubscriptionName() {
    return queueSubscriptionName;
  }

  public synchronized long getWaitForServerStartupTimeout() {
    return waitForServerStartupTimeout;
  }

  public synchronized SubscriptionType getExclusiveSubscriptionTypeForSimpleConsumers() {
    return useExclusiveSubscriptionsForSimpleConsumers
        ? SubscriptionType.Exclusive
        : SubscriptionType.Shared;
  }
}
