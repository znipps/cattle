package io.cattle.iaas.lb.api.action;

import io.cattle.iaas.lb.api.service.LoadBalancerApiService;
import io.cattle.platform.api.action.ActionHandler;
import io.cattle.platform.core.constants.LoadBalancerConstants;
import io.cattle.platform.core.dao.GenericMapDao;
import io.cattle.platform.core.model.LoadBalancer;
import io.cattle.platform.core.model.LoadBalancerHostMap;
import io.cattle.platform.json.JsonMapper;
import io.cattle.platform.object.ObjectManager;
import io.cattle.platform.object.process.ObjectProcessManager;
import io.cattle.platform.object.util.DataAccessor;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

public class LoadBalancerSetHostsActionHandler implements ActionHandler {
    @Inject
    JsonMapper jsonMapper;

    @Inject
    GenericMapDao mapDao;

    @Inject
    ObjectManager objectManager;

    @Inject
    ObjectProcessManager objectProcessManager;

    @Inject
    LoadBalancerApiService lbService;

    @Override
    public String getName() {
        return LoadBalancerConstants.PROCESS_LB_SET_HOSTS;
    }

    @Override
    public Object perform(String name, Object obj, ApiRequest request) {
        if (!(obj instanceof LoadBalancer)) {
            return null;
        }
        LoadBalancer lb = (LoadBalancer) obj;
        List<? extends Long> newHostIds = DataAccessor.fromMap(request.getRequestObject())
                .withKey(LoadBalancerConstants.FIELD_LB_HOST_IDS).asList(jsonMapper, Long.class);

        if (newHostIds != null) {
            // remove old host set
            removeOldHostMaps(lb, newHostIds);

            // create a new set
            createNewHostMaps(lb, newHostIds);
        }

        return objectManager.reload(lb);
    }

    private void createNewHostMaps(LoadBalancer lb, List<? extends Long> newHostIds) {
        for (Long hostId : newHostIds) {
            lbService.addHostToLoadBalancer(lb, hostId);
        }
    }

    private void removeOldHostMaps(LoadBalancer lb, List<? extends Long> newHostIds) {
        List<? extends LoadBalancerHostMap> existingMaps = mapDao.findToRemove(LoadBalancerHostMap.class,
                LoadBalancer.class, lb.getId());

        List<LoadBalancerHostMap> mapsToRemove = new ArrayList<>();

        for (LoadBalancerHostMap existingMap : existingMaps) {
            if (!newHostIds.contains(existingMap.getHostId())) {
                mapsToRemove.add(existingMap);
            }
        }

        for (LoadBalancerHostMap mapToRemove : mapsToRemove) {
            lbService.removeHostFromLoadBalancer(lb, mapToRemove.getHostId());
        }
    }
}
