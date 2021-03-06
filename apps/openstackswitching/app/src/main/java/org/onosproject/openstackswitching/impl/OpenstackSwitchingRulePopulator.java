/*
* Copyright 2015 Open Networking Laboratory
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*     http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package org.onosproject.openstackswitching.impl;

import org.onlab.packet.Ethernet;
import org.onlab.packet.Ip4Address;
import org.onosproject.core.ApplicationId;
import org.onosproject.net.Device;
import org.onosproject.net.DeviceId;
import org.onosproject.net.Port;
import org.onosproject.net.PortNumber;
import org.onosproject.net.behaviour.ExtensionTreatmentResolver;
import org.onosproject.net.device.DeviceService;
import org.onosproject.net.driver.DefaultDriverData;
import org.onosproject.net.driver.DefaultDriverHandler;
import org.onosproject.net.driver.Driver;
import org.onosproject.net.driver.DriverHandler;
import org.onosproject.net.driver.DriverService;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.TrafficTreatment;
import org.onosproject.net.flow.instructions.ExtensionTreatment;
import org.onosproject.net.flow.instructions.ExtensionPropertyException;
import org.onosproject.net.flow.instructions.ExtensionTreatmentType;
import org.onosproject.net.flowobjective.DefaultForwardingObjective;
import org.onosproject.net.flowobjective.FlowObjectiveService;
import org.onosproject.net.flowobjective.ForwardingObjective;
import org.onosproject.openstackswitching.OpenstackNetwork;
import org.onosproject.openstackswitching.OpenstackPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Map;

/**
 * Populates switching flow rules.
 */
public class OpenstackSwitchingRulePopulator {

    private static Logger log = LoggerFactory
            .getLogger(OpenstackSwitchingRulePopulator.class);
    private static final int SWITCHING_RULE_PRIORITY = 30000;
    private static final int EAST_WEST_ROUTING_RULE_PRIORITY = 29000;
    private static final int TUNNELTAG_RULE_PRIORITY = 30000;

    private FlowObjectiveService flowObjectiveService;
    private DriverService driverService;
    private DeviceService deviceService;
    private OpenstackRestHandler restHandler;
    private ApplicationId appId;

    private Collection<OpenstackNetwork> openstackNetworkList;
    private Collection<OpenstackPort> openstackPortList;

    /**
     * Creates OpenstackSwitchingRulPopulator.
     *
     * @param appId application id
     * @param flowObjectiveService FlowObjectiveService reference
     * @param deviceService DeviceService reference
     * @param restHandler OpenstackRestHandler reference
     * @param driverService DriverService reference
     */
    public OpenstackSwitchingRulePopulator(ApplicationId appId,
                                           FlowObjectiveService flowObjectiveService,
                                           DeviceService deviceService,
                                           OpenstackRestHandler restHandler,
                                           DriverService driverService) {
        this.flowObjectiveService = flowObjectiveService;
        this.deviceService = deviceService;
        this.driverService = driverService;
        this.restHandler = restHandler;
        this.appId = appId;

        openstackNetworkList = restHandler.getNetworks();
        openstackPortList = restHandler.getPorts();
    }


    /**
     * Populates flow rules for the VM created.
     *
     * @param device device to populate rules to
     * @param port port for the VM created
     */
    public void populateSwitchingRules(boolean doNotPushFlow, Device device, Port port) {
        if (doNotPushFlow) {
            return;
        }
        populateFlowRulesForTunnelTag(device, port);
        populateFlowRulesForTrafficToSameCnode(device, port);
        populateFlowRulesForTrafficToDifferentCnode(device, port);
    }

    /**
     * Populate the flow rules for tagging tunnelId according to which inport is came from.
     *
     * @param device device to put the rules
     * @param port port info of the VM
     */
    private void populateFlowRulesForTunnelTag(Device device, Port port) {
        Ip4Address vmIp = getFixedIpAddressForPort(port.annotations().value("portName"));
        String portName = port.annotations().value("portName");
        String vni = getVniForPort(portName);

        if (vmIp != null) {
            setFlowRuleForTunnelTag(device.id(), port, vni);
        }
    }

    private void setFlowRuleForTunnelTag(DeviceId deviceId, Port port, String vni) {

        TrafficSelector.Builder sBuilder = DefaultTrafficSelector.builder();
        TrafficTreatment.Builder tBuilder = DefaultTrafficTreatment.builder();

        sBuilder.matchEthType(Ethernet.TYPE_IPV4)
                .matchInPort(port.number());

        tBuilder.setTunnelId(Long.parseLong(vni));

        ForwardingObjective fo = DefaultForwardingObjective.builder()
                .withSelector(sBuilder.build())
                .withTreatment(tBuilder.build())
                .withPriority(TUNNELTAG_RULE_PRIORITY)
                .withFlag(ForwardingObjective.Flag.SPECIFIC)
                .fromApp(appId)
                .add();

        flowObjectiveService.forward(deviceId, fo);
    }

    /**
     * Populates the flow rules for traffic to VMs in the same Cnode as the sender.
     *
     * @param device device to put the rules
     * @param port port info of the VM
     */
    private void populateFlowRulesForTrafficToSameCnode(Device device, Port port) {
        Ip4Address vmIp = getFixedIpAddressForPort(port.annotations().value("portName"));
        String portName = port.annotations().value("portName");
        String vni = getVniForPort(portName);

        if (vmIp != null) {
            setFlowRuleForVMsInSameCnode(vmIp, device.id(), port, vni);
        }
    }

    /**
     * Sets the flow rules for traffic between VMs in the same Cnode.
     *
     * @param ip4Address VM IP address
     * @param id device ID to put rules
     * @param port VM port
     * @param vni VM VNI
     */
    private void setFlowRuleForVMsInSameCnode(Ip4Address ip4Address, DeviceId id,
                                              Port port, String vni) {

        //For L2 Switching Case
        TrafficSelector.Builder sBuilder = DefaultTrafficSelector.builder();
        TrafficTreatment.Builder tBuilder = DefaultTrafficTreatment.builder();

        sBuilder.matchEthType(Ethernet.TYPE_IPV4)
                .matchIPDst(ip4Address.toIpPrefix())
                .matchTunnelId(Long.parseLong(vni));

        tBuilder.setOutput(port.number());

        ForwardingObjective fo = DefaultForwardingObjective.builder()
                .withSelector(sBuilder.build())
                .withTreatment(tBuilder.build())
                .withPriority(SWITCHING_RULE_PRIORITY)
                .withFlag(ForwardingObjective.Flag.SPECIFIC)
                .fromApp(appId)
                .add();

        flowObjectiveService.forward(id, fo);
    }

    /**
     * Populates the flow rules for traffic to VMs in different Cnode using
     * Nicira extention.
     *
     * @param device device to put rules
     * @param port port information of the VM
     */
    private void populateFlowRulesForTrafficToDifferentCnode(Device device, Port port) {
        String portName = port.annotations().value("portName");
        String channelId = device.annotations().value("channelId");
        Ip4Address hostIpAddress = Ip4Address.valueOf(channelId.split(":")[0]);
        Ip4Address fixedIp = getFixedIpAddressForPort(portName);
        String vni = getVniForPort(portName);
        deviceService.getAvailableDevices().forEach(d -> {
            if (!d.equals(device)) {
                deviceService.getPorts(d.id()).forEach(p -> {
                    String pName = p.annotations().value("portName");
                    if (!p.equals(port) && vni.equals(getVniForPort(pName))) {
                        String cidx = d.annotations().value("channelId");
                        Ip4Address hostIpx = Ip4Address.valueOf(cidx.split(":")[0]);
                        Ip4Address fixedIpx = getFixedIpAddressForPort(pName);
                        if (port.isEnabled() ||
                                port.annotations().value("portName").startsWith(
                                        OpenstackSwitchingManager.PORTNAME_PREFIX_ROUTER)) {
                            setVxLanFlowRule(vni, device.id(), hostIpx, fixedIpx);
                            setVxLanFlowRule(vni, d.id(), hostIpAddress, fixedIp);
                        }
                    }
                });
            }
        });
    }

    /**
     * Sets the flow rules between traffic from VMs in different Cnode.
     *
     * @param vni  VNI
     * @param deviceId device ID
     * @param hostIp host IP of the VM
     * @param vmIp fixed IP of the VM
     */
    private void setVxLanFlowRule(String vni, DeviceId deviceId, Ip4Address hostIp,
                                  Ip4Address vmIp) {
        TrafficSelector.Builder sBuilder = DefaultTrafficSelector.builder();
        TrafficTreatment.Builder tBuilder = DefaultTrafficTreatment.builder();

        sBuilder.matchEthType(Ethernet.TYPE_IPV4)
                .matchTunnelId(Long.parseLong(vni))
                .matchIPDst(vmIp.toIpPrefix());
        tBuilder.extension(buildNiciraExtenstion(deviceId, hostIp), deviceId)
                .setOutput(getTunnelPort(deviceId));

        ForwardingObjective fo = DefaultForwardingObjective.builder()
                .withSelector(sBuilder.build())
                .withTreatment(tBuilder.build())
                .withPriority(SWITCHING_RULE_PRIORITY)
                .withFlag(ForwardingObjective.Flag.SPECIFIC)
                .fromApp(appId)
                .add();

        flowObjectiveService.forward(deviceId, fo);
    }

    /**
     * Returns OpenstackPort object for the Port reference given.
     *
     * @param port Port object
     * @return OpenstackPort reference, or null when not found
     */
    public OpenstackPort openstackPort(Port port) {
        String uuid = port.annotations().value("portName").substring(3);
        return openstackPortList.stream().filter(p -> p.id().startsWith(uuid))
                .findAny().orElse(null);
    }

    /**
     * Remove flows rules for the removed VM.
     *
     * @param removedPort removedport info
     * @param openstackPortInfoMap openstackPortInfoMap
     */
    public void removeSwitchingRules(boolean doNotPushFlows, Port removedPort, Map<String,
            OpenstackPortInfo> openstackPortInfoMap) {
        if (doNotPushFlows) {
            return;
        }
        OpenstackPortInfo openstackPortInfo = openstackPortInfoMap
                .get(removedPort.annotations().value("portName"));

        DeviceId deviceId = openstackPortInfo.deviceId();
        Ip4Address vmIp = openstackPortInfo.ip();
        PortNumber portNumber = removedPort.number();
        long vni = openstackPortInfo.vni();

        removeFlowRuleForTunnelTag(deviceId, portNumber);
        removeFlowRuleForVMsInSameCnode(deviceId, vmIp, vni);
        removeFlowRuleForVMsInDiffrentCnode(removedPort, deviceId, vmIp, vni, openstackPortInfoMap);
    }

    /**
     * Removes flow rules for tagging tunnelId.
     *
     * @param deviceId device id
     * @param portNumber port number
     */
    private void removeFlowRuleForTunnelTag(DeviceId deviceId, PortNumber portNumber) {
        TrafficSelector.Builder sBuilder = DefaultTrafficSelector.builder();
        TrafficTreatment.Builder tBuilder = DefaultTrafficTreatment.builder();

        sBuilder.matchEthType(Ethernet.TYPE_IPV4)
                .matchInPort(portNumber);

        ForwardingObjective fo = DefaultForwardingObjective.builder()
                .withSelector(sBuilder.build())
                .withTreatment(tBuilder.build())
                .withPriority(TUNNELTAG_RULE_PRIORITY)
                .withFlag(ForwardingObjective.Flag.SPECIFIC)
                .fromApp(appId)
                .remove();

        flowObjectiveService.forward(deviceId, fo);
    }

    /**
     * Removes the flow rules for traffic between VMs in the same Cnode.
     *
     * @param deviceId device id on which removed VM was run
     * @param vmIp ip of the removed VM
     * @param vni vni which removed VM was belonged
     */
    private void removeFlowRuleForVMsInSameCnode(DeviceId deviceId, Ip4Address vmIp, long vni) {
        TrafficSelector.Builder sBuilder = DefaultTrafficSelector.builder();

        sBuilder.matchEthType(Ethernet.TYPE_IPV4)
                .matchIPDst(vmIp.toIpPrefix())
                .matchTunnelId(vni);

        ForwardingObjective fo = DefaultForwardingObjective.builder()
                .withSelector(sBuilder.build())
                .withTreatment(DefaultTrafficTreatment.builder().build())
                .withFlag(ForwardingObjective.Flag.SPECIFIC)
                .withPriority(SWITCHING_RULE_PRIORITY)
                .fromApp(appId)
                .remove();

        flowObjectiveService.forward(deviceId, fo);
    }

    /**
     * Removes the flow rules for traffic between VMs in the different Cnode.
     *
     * @param removedPort removedport info
     * @param deviceId device id on which removed VM was run
     * @param vmIp ip of the removed VM
     * @param vni vni which removed VM was belonged
     * @param openstackPortInfoMap openstackPortInfoMap
     */
    private void removeFlowRuleForVMsInDiffrentCnode(Port removedPort, DeviceId deviceId, Ip4Address vmIp,
                                                     long vni, Map<String, OpenstackPortInfo> openstackPortInfoMap) {

        final boolean anyPortRemainedInSameCnode
                = checkIfAnyPortRemainedInSameCnode(removedPort, deviceId, vni, openstackPortInfoMap);

        openstackPortInfoMap.forEach((port, portInfo) -> {
            if (portInfo.vni() == vni && !portInfo.deviceId().equals(deviceId)) {
                removeVxLanFlowRule(portInfo.deviceId(), vmIp, vni);
                if (!anyPortRemainedInSameCnode) {
                    removeVxLanFlowRule(deviceId, portInfo.ip(), vni);
                }
            }
        });
    }

    /**
     * Removes the flow rules between traffic from VMs in different Cnode.
     *
     * @param deviceId device id
     * @param vmIp ip
     * @param vni vni which removed VM was belonged
     */
    private void removeVxLanFlowRule(DeviceId deviceId, Ip4Address vmIp, long vni) {
        TrafficSelector.Builder sBuilder = DefaultTrafficSelector.builder();

        sBuilder.matchEthType(Ethernet.TYPE_IPV4)
                .matchTunnelId(vni)
                .matchIPDst(vmIp.toIpPrefix());

        ForwardingObjective fo = DefaultForwardingObjective.builder()
                .withSelector(sBuilder.build())
                .withTreatment(DefaultTrafficTreatment.builder().build())
                .withFlag(ForwardingObjective.Flag.SPECIFIC)
                .withPriority(SWITCHING_RULE_PRIORITY)
                .fromApp(appId)
                .remove();

        flowObjectiveService.forward(deviceId, fo);
    }

    /**
     * Checks if there is any port remained with same vni at the device, on which the removed VM was run.
     *
     * @param removedPort removedport info
     * @param deviceId device id on which removed VM was run
     * @param vni vni which removed VM was belonged
     * @param openstackPortInfoMap openstackPortInfoMap
     * @return true if there is, false otherwise
     */
    private boolean checkIfAnyPortRemainedInSameCnode(Port removedPort, DeviceId deviceId, long vni,
                                                    Map<String, OpenstackPortInfo> openstackPortInfoMap) {

        for (Map.Entry<String, OpenstackPortInfo> entry : openstackPortInfoMap.entrySet()) {
            if (!removedPort.annotations().value("portName").equals(entry.getKey())) {
                if (entry.getValue().vni() == vni && entry.getValue().deviceId().equals(deviceId)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Returns the VNI of the VM of the port.
     *
     * @param portName VM port
     * @return VNI
     */
    private String getVniForPort(String portName) {
        String uuid = portName.substring(3);
        OpenstackPort port = openstackPortList.stream()
                .filter(p -> p.id().startsWith(uuid))
                .findAny().orElse(null);
        if (port == null) {
            log.debug("No port information for port {}", portName);
            return null;
        }

        OpenstackNetwork network = openstackNetworkList.stream()
                .filter(n -> n.id().equals(port.networkId()))
                .findAny().orElse(null);
        if (network == null) {
            log.warn("No VNI information for network {}", port.networkId());
            return null;
        }

        return network.segmentId();
    }

    /**
     * Returns the Fixed IP address of the VM.
     *
     * @param portName VM port info
     * @return IP address of the VM
     */
    private Ip4Address getFixedIpAddressForPort(String portName) {

        String uuid = portName.substring(3);
        OpenstackPort port = openstackPortList.stream()
                .filter(p -> p.id().startsWith(uuid))
                .findFirst().orElse(null);

        if (port == null) {
            log.error("There is no port information for port name {}", portName);
            return null;
        }

        if (port.fixedIps().isEmpty()) {
            log.error("There is no fixed IP info in the port information");
            return null;
        }

        return (Ip4Address) port.fixedIps().values().toArray()[0];
    }

    private ExtensionTreatment buildNiciraExtenstion(DeviceId id, Ip4Address hostIp) {
        Driver driver = driverService.getDriver(id);
        DriverHandler driverHandler = new DefaultDriverHandler(new DefaultDriverData(driver, id));
        ExtensionTreatmentResolver resolver = driverHandler.behaviour(ExtensionTreatmentResolver.class);

        ExtensionTreatment extensionInstruction =
                resolver.getExtensionInstruction(
                        ExtensionTreatmentType.ExtensionTreatmentTypes.NICIRA_SET_TUNNEL_DST.type());

        try {
            extensionInstruction.setPropertyValue("tunnelDst", hostIp);
        } catch (ExtensionPropertyException e) {
            log.error("Error setting Nicira extension setting {}", e);
        }

        return  extensionInstruction;
    }

    private PortNumber getTunnelPort(DeviceId deviceId) {
        Port port = deviceService.getPorts(deviceId).stream()
                .filter(p -> p.annotations().value("portName").equals(
                        OpenstackSwitchingManager.PORTNAME_PREFIX_TUNNEL))
                .findAny().orElse(null);

        if (port == null) {
            log.error("No TunnelPort was created.");
            return null;
        }
        return port.number();
    }
}
