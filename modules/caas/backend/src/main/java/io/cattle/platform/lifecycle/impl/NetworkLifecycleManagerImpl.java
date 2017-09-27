package io.cattle.platform.lifecycle.impl;

import static io.cattle.platform.object.util.DataAccessor.*;

import io.cattle.platform.core.constants.InstanceConstants;
import io.cattle.platform.core.constants.NetworkConstants;
import io.cattle.platform.core.model.Instance;
import io.cattle.platform.core.model.Network;
import io.cattle.platform.core.model.Stack;
import io.cattle.platform.core.util.ServiceUtil;
import io.cattle.platform.core.util.SystemLabels;
import io.cattle.platform.lifecycle.NetworkLifecycleManager;
import io.cattle.platform.lifecycle.util.LifecycleException;
import io.cattle.platform.network.IPAssignment;
import io.cattle.platform.network.NetworkService;
import io.cattle.platform.object.ObjectManager;
import io.cattle.platform.object.util.DataAccessor;
import io.cattle.platform.resource.pool.PooledResource;
import io.cattle.platform.resource.pool.PooledResourceOptions;
import io.cattle.platform.resource.pool.ResourcePoolManager;
import io.cattle.platform.resource.pool.util.ResourcePoolConstants;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;

public class NetworkLifecycleManagerImpl implements NetworkLifecycleManager {

    ObjectManager objectManager;
    NetworkService networkService;
    ResourcePoolManager poolManager;

    public NetworkLifecycleManagerImpl(ObjectManager objectManager, NetworkService networkService, ResourcePoolManager poolManager) {
        super();
        this.objectManager = objectManager;
        this.networkService = networkService;
        this.poolManager = poolManager;
    }

    @Override
    public void create(Instance instance, Stack stack) throws LifecycleException {
        setupRequestedIp(instance);
        Network network = resolveNetworkMode(instance);
        setDns(instance, network);
    }

    @Override
    public void preRemove(Instance instance) {
        Network network = objectManager.loadResource(Network.class,
                fieldLong(instance, InstanceConstants.FIELD_PRIMARY_NETWORK_ID));

        releaseMacAddress(instance, network);
        releaseIpAddress(instance, network);
    }

    @Override
    public void assignNetworkResources(Instance instance) throws LifecycleException {
        Long networkId = fieldLong(instance, InstanceConstants.FIELD_PRIMARY_NETWORK_ID);
        Network network = objectManager.loadResource(Network.class, networkId);
        if (network == null) {
            return;
        }

        assignIpAddress(instance, network);
        assignMacAddress(instance, network);
        setupCNILabels(instance, network);
    }

    private void setupRequestedIp(Instance instance) {
        String ip = getLabel(instance, SystemLabels.LABEL_REQUESTED_IP);
        if (StringUtils.isNotBlank(ip)) {
            setField(instance, InstanceConstants.FIELD_REQUESTED_IP_ADDRESS, ip);
        }
    }

    private Network resolveNetworkMode(Instance instance) throws LifecycleException {
        String mode = networkService.getNetworkMode(DataAccessor.getFields(instance));
        setField(instance, InstanceConstants.FIELD_NETWORK_MODE, mode);

        Network network = networkService.resolveNetwork(instance.getAccountId(), mode);
        if (network == null && StringUtils.isNotBlank(mode) && !instance.getNativeContainer()) {
            throw new LifecycleException(String.format("Failed to find network for networkMode %s", mode));
        }

        if (network != null) {
            instance.setNetworkId(network.getId());
            setField(instance, InstanceConstants.FIELD_NETWORK_IDS, Collections.singletonList(network.getId()));
            setField(instance, InstanceConstants.FIELD_PRIMARY_NETWORK_ID, network.getId());
        }

        return network;
    }

    protected void setDns(Instance instance, Network network) {
        boolean addDns = DataAccessor.fromMap(DataAccessor.fieldMapRO(instance, InstanceConstants.FIELD_LABELS))
                .withKey(SystemLabels.LABEL_USE_RANCHER_DNS)
                .withDefault(true)
                .as(Boolean.class);
        if (!addDns) {
            return;
        }

        for (String dns : DataAccessor.fieldStringList(network, NetworkConstants.FIELD_DNS)) {
            List<String> dnsList = appendToFieldStringList(instance, InstanceConstants.FIELD_DNS, dns);
            setField(instance, InstanceConstants.FIELD_DNS, dnsList);
        }
        if (DataAccessor.fieldStringList(network, NetworkConstants.FIELD_DNS).isEmpty() || instance.getNativeContainer()) {
            if (DataAccessor.fromMap(DataAccessor.fieldMapRO(instance, InstanceConstants.FIELD_LABELS))
                    .withKey(SystemLabels.LABEL_USE_RANCHER_DNS).as(Boolean.class) == null) {
                return;
            }
        }

        // append Rancher search domains and corresponding labels
        List<String> dnsSearchList = DataAccessor.fieldStringList(instance, InstanceConstants.FIELD_DNS_SEARCH);
        Iterator<String> it = dnsSearchList.iterator();
        while (it.hasNext()) {
            String search = it.next();
            if (search.endsWith(String.format(".%s", NetworkConstants.INTERNAL_DNS_SEARCH_DOMAIN))) {
                it.remove();
            }
        }
        String rancherSearchDomain =  ServiceUtil.getContainerNamespace(instance);
        dnsSearchList.add(rancherSearchDomain);
        setField(instance, InstanceConstants.FIELD_DNS_SEARCH, dnsSearchList);
        String searchLabel = dnsSearchList.stream().collect(Collectors.joining(","));
        setLabel(instance, SystemLabels.LABEL_DNS_SEARCH, searchLabel);
        setLabel(instance, SystemLabels.LABEL_USE_RANCHER_DNS, "true");
    }

    private void setupCNILabels(Instance instance, Network network) {
        if (NetworkConstants.KIND_CNI.equals(network.getKind())) {
            String wait = getLabel(instance, SystemLabels.LABEL_CNI_WAIT);
            String netName = getLabel(instance, SystemLabels.LABEL_CNI_NETWORK);
            if (StringUtils.isBlank(wait) || StringUtils.isBlank(netName)) {
                setLabel(instance, SystemLabels.LABEL_CNI_WAIT, "true");
                setLabel(instance, SystemLabels.LABEL_CNI_NETWORK, network.getName());
            }
        }
    }

    private void releaseMacAddress(Instance instance, Network network) {
        poolManager.releaseResource(network, instance, new PooledResourceOptions().withQualifier(ResourcePoolConstants.MAC));
    }

    private void assignMacAddress(Instance instance, Network network) throws LifecycleException {
        String mac = fieldString(instance, InstanceConstants.FIELD_PRIMARY_MAC_ADDRESSS);
        if (StringUtils.isNotBlank(mac)) {
            return;
        }

        String prefix = DataAccessor.field(network, NetworkConstants.FIELD_MAC_PREFIX, String.class);
        if (prefix == null) {
            return;
        }

        PooledResource resource = poolManager.allocateOneResource(network, instance, new PooledResourceOptions().withQualifier(ResourcePoolConstants.MAC));
        if (resource == null) {
            throw new LifecycleException("Failed to allocate MAC from network");
        }

        setField(instance, InstanceConstants.FIELD_PRIMARY_MAC_ADDRESSS, resource.getName());
        setLabel(instance, SystemLabels.LABEL_MAC_ADDRESS, resource.getName().replace(':', '-'));
    }

    private void releaseIpAddress(Instance instance, Network network) {
        networkService.releaseIpAddress(network, instance);
    }

    private void assignIpAddress(Instance instance, Network network) throws LifecycleException {
        String ipAddress = fieldString(instance, InstanceConstants.FIELD_PRIMARY_IP_ADDRESS);
        if (StringUtils.isNotBlank(ipAddress) || !networkService.shouldAssignIpAddress(network)) {
            return;
        }

        IPAssignment assignment = allocateIp(instance, network);
        if (assignment != null) {
            setField(instance, InstanceConstants.FIELD_PRIMARY_IP_ADDRESS, assignment.getIpAddress());
            setField(instance, InstanceConstants.FIELD_MANAGED_IP, "true");
            if (assignment.getSubnet() != null && assignment.getSubnet().getCidrSize() != null) {
                setLabel(instance, SystemLabels.LABEL_IP_ADDRESS,
                        String.format("%s/%d", assignment.getIpAddress(), assignment.getSubnet().getCidrSize()));
            } else {
                setLabel(instance, SystemLabels.LABEL_IP_ADDRESS, assignment.getIpAddress());
            }
        }
    }

    private IPAssignment allocateIp(Instance instance, Network network) throws LifecycleException {
        String requestedIp = fieldString(instance, InstanceConstants.FIELD_REQUESTED_IP_ADDRESS);
        IPAssignment ip = networkService.assignIpAddress(network, instance, requestedIp);
        if (ip == null) {
            throw new LifecycleException("Failed to allocate IP from subnet");
        }

        return ip;
    }

}
