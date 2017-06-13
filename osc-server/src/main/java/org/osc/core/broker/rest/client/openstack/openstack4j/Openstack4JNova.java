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

import org.apache.log4j.Logger;
import org.openstack4j.api.Builders;
import org.openstack4j.model.common.ActionResponse;
import org.openstack4j.model.compute.Action;
import org.openstack4j.model.compute.Flavor;
import org.openstack4j.model.compute.FloatingIP;
import org.openstack4j.model.compute.HostAggregate;
import org.openstack4j.model.compute.InterfaceAttachment;
import org.openstack4j.model.compute.Server;
import org.openstack4j.model.compute.builder.ServerCreateBuilder;
import org.openstack4j.model.compute.ext.AvailabilityZone;
import org.openstack4j.model.compute.ext.Hypervisor;
import org.openstack4j.model.network.Port;
import org.osc.core.broker.service.exceptions.VmidcBrokerInvalidRequestException;
import org.osc.sdk.manager.element.ApplianceBootstrapInformationElement;
import org.osc.sdk.manager.element.ApplianceBootstrapInformationElement.BootstrapFileElement;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public class Openstack4JNova extends BaseOpenstack4jApi {

    private static final Logger log = Logger.getLogger(Openstack4JNova.class);

    private Set<String> regions;

    public static final class CreatedServerDetails {

        private String serverId;
        private String ingressInspectionPortId;
        private String ingressInspectionMacAddr;
        private String egressInspectionPortId;
        private String egressInspectionMacAddr;

        CreatedServerDetails(String serverId, String ingressInspectionPortId, String ingressInspectionMacAddr,
                             String egressInspectionPortId, String egressInspectionMacAddr) {
            this.serverId = serverId;
            this.ingressInspectionPortId = ingressInspectionPortId;
            this.ingressInspectionMacAddr = ingressInspectionMacAddr;
            this.egressInspectionPortId = egressInspectionPortId;
            this.egressInspectionMacAddr = egressInspectionMacAddr;
        }

        public String getServerId() {
            return this.serverId;
        }

        public String getIngressInspectionPortId() {
            return this.ingressInspectionPortId;
        }

        public String getIngressInspectionMacAddr() {
            return this.ingressInspectionMacAddr;
        }

        public String getEgressInspectionPortId() {
            return this.egressInspectionPortId;
        }

        public String getEgressInspectionMacAddr() {
            return this.egressInspectionMacAddr;
        }
    }

    public Openstack4JNova(Endpoint endPoint) {
        super(endPoint);
    }

    public Set<String> listRegions() {
        if (this.regions == null) {
            List<? extends org.openstack4j.model.identity.v2.Endpoint> endpoints = getOs().identity().listTokenEndpoints();
            this.regions = endpoints.stream().map(org.openstack4j.model.identity.v2.Endpoint::getRegion).collect(Collectors.toSet()); // :TODO ADD DISTINCT
        }
        return this.regions;
    }

    // Server APIS
    public CreatedServerDetails createServer(String region, String availabilityZone, String svaName, String imageRef,
                                             String flavorRef, ApplianceBootstrapInformationElement bootstrapInfo, String mgmtNetworkUuid,
                                             String inspectionNetworkUuid, boolean additionalNicForInspection, String sgName) {
        getOs().useRegion(region);

        Port ingressInspectionPort = getOs().networking().port().create(Builders.port().networkId(inspectionNetworkUuid).build());

        Port egressInspectionPort;
        if (additionalNicForInspection) {
            egressInspectionPort = getOs().networking().port().create(Builders.port().networkId(inspectionNetworkUuid).build());
        } else {
            egressInspectionPort = ingressInspectionPort;
        }

        try {
            ServerCreateBuilder sc = Builders.server().name(svaName).flavor(flavorRef).image(imageRef);

            sc.addNetworkPort(ingressInspectionPort.getId());
            sc.networks(Collections.singletonList(mgmtNetworkUuid));

            if (additionalNicForInspection) {
                sc.addNetworkPort(egressInspectionPort.getId());
            }

            for (BootstrapFileElement file : bootstrapInfo.getBootstrapFiles()) {
                sc.addPersonality(file.getName(), Arrays.toString(file.getContent()));
            }
            sc.configDrive(true);

            if (sgName != null) {
                sc.addSecurityGroup(sgName);
            }

            if (availabilityZone != null) {
                sc.availabilityZone(availabilityZone);
            }

            Server server = getOs().compute().servers().boot(sc.build());

            log.info("Server '" + svaName + "' Created with Id: " + server.getId());

            getOs().removeRegion();
            return new CreatedServerDetails(server.getId(), ingressInspectionPort.getId(),
                    ingressInspectionPort.getMacAddress(), egressInspectionPort.getId(),
                    egressInspectionPort.getMacAddress());
        } catch (Exception e) {
            // Server creating failed for some reason, delete the inspection port created
            ActionResponse deleteResponse = getOs().networking().port().delete(ingressInspectionPort.getId());
            if (!deleteResponse.isSuccess()) {
                log.info("Cannot delete ingress inspection port: " + deleteResponse.getFault());
            }
            if (additionalNicForInspection) {
                // If we have multiple interfaces, egress and ingress ports are different else they are the same)
                ActionResponse deleteEgressInspPort = getOs().networking().port().delete(egressInspectionPort.getId());
                if (!deleteEgressInspPort.isSuccess()) {
                    log.info("Cannot delete egress inspection port: " + deleteResponse.getFault());
                }
            }
            throw e;
        }
    }

    public Server getServer(String region, String serverId) {
        getOs().useRegion(region);
        Server server = getOs().compute().servers().get(serverId);
        getOs().removeRegion();
        return server;
    }

    public List<? extends org.openstack4j.model.compute.Server> listServers(String region) {
        getOs().useRegion(region);
        List<? extends org.openstack4j.model.compute.Server> list = getOs().compute().servers().list();
        getOs().removeRegion();
        return list;
    }

    public boolean startServer(String region, String serverId) {
        getOs().useRegion(region);
        ActionResponse action = getOs().compute().servers().action(serverId, Action.START);
        getOs().removeRegion();
        return action.isSuccess();
    }

    public Server getServerByName(String region, String name) {
        String regExName = "^" + name + "$";

        getOs().useRegion(region);
        List<? extends Server> servers = getOs().compute().servers().list(false);
        Optional<? extends Server> firstFound = servers.stream().filter(o -> o.getName().matches(regExName)).findFirst();
        getOs().removeRegion();
        return (firstFound.isPresent()) ? firstFound.get() : null;
    }

    public boolean terminateInstance(String region, String serverId) {
        getOs().useRegion(region);
        ActionResponse actionResponse = getOs().compute().servers().delete(serverId);
        getOs().removeRegion();
        return actionResponse.isSuccess();
    }

    // Floating IP API
    public List<String> getFloatingIpPools(String region) throws Exception {
        getOs().useRegion(region);
        List<String> poolNames = getOs().compute().floatingIps().getPoolNames();
        getOs().removeRegion();
        return poolNames;
    }

    public FloatingIP getFloatingIp(String region, String id) {
        if (id == null) {
            return null;
        }

        getOs().useRegion(region);
        Optional<? extends FloatingIP> first = getOs().compute().floatingIps().list().stream().filter(o -> o.getId().equals(id)).findFirst();
        getOs().removeRegion();

        return (first.isPresent()) ? first.get() : null;
    }

    public void allocateFloatingIpToServer(String region, String serverId, FloatingIP floatingIp) {
        getOs().useRegion(region);
        Server server = getOs().compute().servers().get(serverId);
        getOs().compute().floatingIps().addFloatingIP(server, floatingIp.getFloatingIpAddress());
        getOs().removeRegion();
    }

    /**
     * A synchronous way to allocate floating ip(within ourselfs). Since this is a static method, we would lock on
     * the JCloudUtil class objects which prevents multiple threads from making the floating ip call at the same time.
     * null
     *
     * @throws VmidcBrokerInvalidRequestException in case we get an exception while allocating the floating ip
     */
    public FloatingIP allocateFloatingIp(String region, String poolName, String serverId) throws VmidcBrokerInvalidRequestException {
        getOs().useRegion(region);

        boolean newIPAllocated = false;
        // Find first ip not allocated, add that to this server
        Optional<? extends FloatingIP> foundFloatingIp = getOs().compute().floatingIps().list().stream()
                .filter(floatingIp -> floatingIp.getFixedIpAddress() == null && poolName.equals(floatingIp.getPool()))
                .findFirst();

        FloatingIP ip;
        if (foundFloatingIp.isPresent()) {
            ip = foundFloatingIp.get();
        } else {
            // If ip is still null, allocate new ip from the pool
            FloatingIP floatingIp = getOs().compute().floatingIps().allocateIP(poolName);
            if (floatingIp != null) {
                ip = floatingIp;
                newIPAllocated = true;
            } else {
                throw new IllegalStateException("Ip pool exhausted");
            }
        }
        Server server = getOs().compute().servers().get(serverId);
        if (server == null) {
            throw new VmidcBrokerInvalidRequestException("Server with id: " + serverId + " not found");
        }
        getOs().compute().floatingIps().addFloatingIP(server, ip.getFixedIpAddress(), ip.getFloatingIpAddress());

        if (newIPAllocated) {
            log.info("Deleting Floating IP as we could not able to assosiate it with our SVA " + ip);
            ActionResponse actionResponse = getOs().compute().floatingIps().removeFloatingIP(server, ip.getFloatingIpAddress());
            log.info("Is floating IP successfully removed:" + actionResponse.isSuccess());
        }

        log.info("Allocated Floating ip: " + ip + " To server with Id: " + serverId);
        getOs().removeRegion();
        return ip;
    }

    public synchronized void deleteFloatingIp(String region, String ip, String serverId) {
        getOs().useRegion(region);
        log.info("Deleting Floating ip: " + ip + " with serverId: " + serverId);
        Server server = getOs().compute().servers().get(serverId);
        ActionResponse actionResponse = getOs().compute().floatingIps().removeFloatingIP(server, ip);
        if (!actionResponse.isSuccess()) {
            log.warn("IP : " + ip + " in server id: " + serverId + " not found.");
        }
        getOs().removeRegion();
    }

    // Flavor APIS
    public String createFlavor(String region, String id, String flavorName, int diskInGb, int ramInMB, int cpus) {
        getOs().useRegion(region);
        Flavor flavor = getOs().compute().flavors().create(
                Builders.flavor().disk(diskInGb).ram(ramInMB).vcpus(cpus).name(flavorName).id(id).build()
        );
        getOs().removeRegion();
        return flavor.getId();
    }

    public Flavor getFlavorById(String region, String id) {
        getOs().useRegion(region);
        Flavor flavor = getOs().compute().flavors().get(id);
        getOs().removeRegion();
        return flavor;
    }

    public void deleteFlavorById(String region, String id) {
        getOs().useRegion(region);
        ActionResponse actionResponse = getOs().compute().flavors().delete(id);
        if (!actionResponse.isSuccess()) {
            log.warn("Image Id: " + id + " not found.");
        }
        getOs().removeRegion();
    }

    // Host Aggregates
    public List<? extends HostAggregate> listHostAggregates(String region) {
        getOs().useRegion(region);
        List<? extends HostAggregate> list = getOs().compute().hostAggregates().list();
        getOs().removeRegion();
        return list;
    }

    public HostAggregate getHostAggregateById(String region, String id) {
        getOs().useRegion(region);
        HostAggregate hostAggregate = getOs().compute().hostAggregates().get(id);
        getOs().removeRegion();
        return hostAggregate;
    }

    // Interface Attachment
    public List<? extends InterfaceAttachment> getVmAttachedNetworks(String region, String serverId) {
        getOs().useRegion(region);
        List<? extends InterfaceAttachment> list = getOs().compute().servers().interfaces().list(serverId);
        getOs().removeRegion();
        return list;
    }

    // Availability Zone
    public List<? extends AvailabilityZone> listAvailabilityZones(String region) {
        getOs().useRegion(region);
        List<? extends AvailabilityZone> list = getOs().compute().zones().list();
        getOs().removeRegion();
        return list;
    }

    public List<? extends AvailabilityZone> getAvailabilityZonesDetail(String region) throws Exception {
        getOs().useRegion(region);
        List<? extends AvailabilityZone> list = getOs().compute().zones().list(true);
        getOs().removeRegion();
        return list;
    }

    public static HostAvailabilityZoneMapping getMapping(List<? extends AvailabilityZone> availabilityZones)
            throws Exception {
        return new HostAvailabilityZoneMapping(availabilityZones);
    }

    // TODO check if it's ok - different approach than jcloud
    public Set<String> getComputeHosts(String region) throws Exception {
        getOs().useRegion(region);
        List<? extends Hypervisor> list = getOs().compute().hypervisors().list();
        Set<String> strings = list.stream().map(Hypervisor::getHypervisorHostname).collect(Collectors.toSet());
        getOs().removeRegion();
        return strings;
    }

}
