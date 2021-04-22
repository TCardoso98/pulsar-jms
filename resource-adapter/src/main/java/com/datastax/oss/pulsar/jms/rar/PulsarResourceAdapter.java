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
package com.datastax.oss.pulsar.jms.rar;

import javax.resource.ResourceException;
import javax.resource.spi.ActivationSpec;
import javax.resource.spi.BootstrapContext;
import javax.resource.spi.ResourceAdapter;
import javax.resource.spi.ResourceAdapterInternalException;
import javax.resource.spi.endpoint.MessageEndpointFactory;
import javax.transaction.xa.XAResource;

public class PulsarResourceAdapter implements ResourceAdapter {

  @Override
  public void start(BootstrapContext bootstrapContext) throws ResourceAdapterInternalException {}

  @Override
  public void stop() {}

  @Override
  public void endpointActivation(
      MessageEndpointFactory messageEndpointFactory, ActivationSpec activationSpec)
      throws ResourceException {}

  @Override
  public void endpointDeactivation(
      MessageEndpointFactory messageEndpointFactory, ActivationSpec activationSpec) {}

  @Override
  public XAResource[] getXAResources(ActivationSpec[] activationSpecs) throws ResourceException {
    return new XAResource[0];
  }
}
