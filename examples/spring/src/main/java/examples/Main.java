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
package examples;

import com.datastax.oss.pulsar.jms.PulsarConnectionFactory;
import java.util.HashMap;
import java.util.Map;
import javax.jms.ConnectionFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jms.DefaultJmsListenerContainerFactoryConfigurer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.jms.annotation.EnableJms;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.jms.config.JmsListenerContainerFactory;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.support.converter.MappingJackson2MessageConverter;
import org.springframework.jms.support.converter.MessageConverter;
import org.springframework.jms.support.converter.MessageType;

@SpringBootApplication
@EnableJms
public class Main {

  public static void main(String[] args) {

    ConfigurableApplicationContext context = SpringApplication.run(Main.class, args);

    JmsTemplate jmsTemplate = context.getBean(JmsTemplate.class);

    // Send a message with a POJO - the template reuse the message converter
    for (int i = 0; i < 100; i++) {
      jmsTemplate.convertAndSend("IN_QUEUE", new Email("info" + i + "@example.com", "Hello"));
    }
  }

  @Bean
  public ConnectionFactory connectionFactory() throws Exception {
    Map<String, Object> configuration = new HashMap<>();
    configuration.put("brokerServiceUrl", "http://localhost:8080");
    configuration.put("webServiceUrl", "http://localhost:8080");
    configuration.put("jms.enableClientSideFeatures", "true");

    // By default in Pulsar transactions are disabled
    // add enableTransaction=true to your PulsarConnectionFactory configuration
    // and also you will have to enable transaction support in your Pulsar broker
    configuration.put("enableTransaction", "false");
    return new PulsarConnectionFactory(configuration);
  }

  @Bean
  public JmsListenerContainerFactory<?> myFactory(
      ConnectionFactory connectionFactory,
      DefaultJmsListenerContainerFactoryConfigurer configurer) {
    DefaultJmsListenerContainerFactory factory = new DefaultJmsListenerContainerFactory();
    // This provides all boot's default to this factory, including the message converter
    configurer.configure(factory, connectionFactory);

    // By default in Pulsar transactions are disabled
    // add enableTransaction=true to your PulsarConnectionFactory configuration
    // and also you will have to enable transaction support in your Pulsar broker
    factory.setSessionTransacted(false);

    // You could still override some of Boot's default if necessary.
    return factory;
  }

  @Bean // Serialize message content to json using TextMessage
  public MessageConverter jacksonJmsMessageConverter() {
    MappingJackson2MessageConverter converter = new MappingJackson2MessageConverter();
    converter.setTargetType(MessageType.TEXT);
    converter.setTypeIdPropertyName("_type");
    return converter;
  }
}
