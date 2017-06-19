/*******************************************************************************
 * Copyright (c) Intel Corporation
 * Copyright (c) 2017
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package org.osc.core.broker.rest.client.openstack.openstack4j;

import org.openstack4j.api.OSClient;
import org.openstack4j.api.client.IOSClientBuilder;
import org.openstack4j.api.types.Facing;
import org.openstack4j.core.transport.Config;
import org.openstack4j.model.common.Identifier;
import org.openstack4j.model.identity.v3.Token;
import org.openstack4j.openstack.OSFactory;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;

public class KeystoneProvider {

    private static KeystoneProvider instance = null;
    private static OSClient.OSClientV3 os;
    private Endpoint endPoint;

    protected KeystoneProvider() {
    }

    protected KeystoneProvider(Endpoint endPoint) {
        this.endPoint = endPoint;
    }
    public static KeystoneProvider getInstance(Endpoint endPoint) {
        if(instance == null || !instance.endPoint.equals(endPoint)) {
            instance = new KeystoneProvider(endPoint);
        }
        return instance;
    }

    OSClient.OSClientV3 getAvailableSession(){
        OSClient.OSClientV3 localOs;
        Config config = Config.newConfig().withSSLContext(this.endPoint.getSslContext()).withHostnameVerifier((hostname, session) -> true);
        if(os == null){
            String endpointURL;
            try {
                endpointURL = prepareEndpointURL(this.endPoint);
            } catch (URISyntaxException | MalformedURLException e) {
                throw new RuntimeException(e.getMessage(), e);
            }

            // LOGGER
            OSFactory.enableHttpLoggingFilter(true);

            Identifier domainIdentifier = Identifier.byName(this.endPoint.getDomain());

            IOSClientBuilder.V3 keystoneV3Builder = OSFactory.builderV3().perspective(Facing.ADMIN)
                    .endpoint(endpointURL)
                    .credentials(this.endPoint.getUser(), this.endPoint.getPassword(), domainIdentifier)
                    .scopeToProject(Identifier.byName(this.endPoint.getTenant()), domainIdentifier)
                    .withConfig(config);

            localOs = keystoneV3Builder.authenticate();
        } else {
            Token token = os.getToken();
            localOs = OSFactory.clientFromToken(token, Facing.ADMIN, config);
        }

        os = localOs;
        return localOs;
    }

    private String prepareEndpointURL(Endpoint endPoint) throws URISyntaxException, MalformedURLException {
        String schema = endPoint.isHttps() ? "https" : "http";
        URI uri = new URI(schema, null, endPoint.getEndPointIP(), 5000, "/v3", null, null);
        return uri.toURL().toString();
    }

}
