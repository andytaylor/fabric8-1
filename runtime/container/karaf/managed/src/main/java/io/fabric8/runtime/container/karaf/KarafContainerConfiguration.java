/**
 *  Copyright 2005-2015 Red Hat, Inc.
 *
 *  Red Hat licenses this file to you under the Apache License, version
 *  2.0 (the "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 *  implied.  See the License for the specific language governing
 *  permissions and limitations under the License.
 */
package io.fabric8.runtime.container.karaf;

import io.fabric8.api.gravia.MavenCoordinates;
import io.fabric8.runtime.container.ContainerConfiguration;

import java.io.IOException;
import java.util.Properties;


/**
 * The managed container configuration
 *
 * @since 26-Feb-2014
 */
public final class KarafContainerConfiguration extends ContainerConfiguration {

    public static final String DEFAULT_JAVAVM_ARGUMENTS = "-Xmx512m ";

    @Override
    protected void validate() {
        if (getMavenCoordinates().isEmpty()) {
            Properties properties = new Properties();
            try {
                properties.load(getClass().getResourceAsStream("version.properties"));
            } catch (IOException ex) {
                throw new IllegalStateException("Cannot load version.properties", ex);
            }
            String projectVersion = properties.getProperty("project.version");
            addMavenCoordinates(MavenCoordinates.create("io.fabric8", "fabric8-karaf", projectVersion, "zip", null));
        }
        super.validate();
    }
}
