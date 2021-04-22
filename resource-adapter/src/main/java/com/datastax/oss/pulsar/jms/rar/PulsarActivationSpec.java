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
import javax.resource.spi.InvalidPropertyException;
import javax.resource.spi.ResourceAdapter;

public class PulsarActivationSpec implements javax.resource.spi.ActivationSpec {

  private ResourceAdapter resourceAdapter;
  private String destination;
  private String destinationType;

  public String getDestination() {
    return destination;
  }

  public void setDestination(String destination) {
    this.destination = destination;
  }

  public String getDestinationType() {
    return destinationType;
  }

  public void setDestinationType(String destinationType) {
    this.destinationType = destinationType;
  }

  @Override
  public void validate() throws InvalidPropertyException {}

  @Override
  public ResourceAdapter getResourceAdapter() {
    return resourceAdapter;
  }

  @Override
  public void setResourceAdapter(ResourceAdapter resourceAdapter) throws ResourceException {
    this.resourceAdapter = resourceAdapter;
  }

  @Override
  public String toString() {
    return "PulsarActivationSpec{"
        + "resourceAdapter="
        + resourceAdapter
        + ", destination='"
        + destination
        + '\''
        + ", destinationType='"
        + destinationType
        + '\''
        + '}';
  }
}
