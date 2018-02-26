/*
 * Copyright (c) 2016 VMware, Inc. All Rights Reserved.
 *
 * This product is licensed to you under the Apache License, Version 2.0 (the "License").
 * You may not use this product except in compliance with the License.
 *
 * This product may include a number of subcomponents with separate copyright notices
 * and license terms. Your use of these subcomponents is subject to the terms and
 * conditions of the subcomponent's license, as noted in the LICENSE file.
 */

package com.vmware.admiral.request.allocation.filter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.stream.Collectors;

import com.vmware.admiral.common.util.QueryUtil;
import com.vmware.admiral.common.util.ServiceDocumentQuery;
import com.vmware.admiral.compute.ContainerHostService.ContainerHostType;
import com.vmware.admiral.compute.container.CompositeComponentFactoryService;
import com.vmware.admiral.compute.container.CompositeComponentService.CompositeComponent;
import com.vmware.admiral.compute.container.CompositeDescriptionService.CompositeDescription;
import com.vmware.admiral.compute.container.ContainerDescriptionService.ContainerDescription;
import com.vmware.admiral.compute.container.ServiceNetwork;
import com.vmware.admiral.compute.container.network.ContainerNetworkDescriptionService;
import com.vmware.admiral.compute.container.network.ContainerNetworkDescriptionService.ContainerNetworkDescription;
import com.vmware.admiral.compute.container.network.ContainerNetworkService.ContainerNetworkState;
import com.vmware.admiral.compute.container.volume.VolumeUtil;
import com.vmware.admiral.request.PlacementHostSelectionTaskService;
import com.vmware.admiral.request.PlacementHostSelectionTaskService.PlacementHostSelectionTaskState;
import com.vmware.admiral.request.utils.RequestUtils;
import com.vmware.photon.controller.model.resources.ResourceState;
import com.vmware.xenon.common.Operation;
import com.vmware.xenon.common.ServiceHost;
import com.vmware.xenon.common.UriUtils;
import com.vmware.xenon.services.common.QueryTask;
import com.vmware.xenon.services.common.QueryTask.QuerySpecification;

/**
 * A filter implementing {@link HostSelectionFilter} in order to provide affinity and network name
 * resolution in case the {@link ContainerDescription} specifies <code>networks</code> property.
 */
public class ContainerToNetworkAffinityHostFilter
        implements HostSelectionFilter<PlacementHostSelectionTaskState> {
    private final Map<String, ServiceNetwork> networks;
    private final ServiceHost host;
    private final String networkMode;

    private static final String NO_KV_STORE = "NONE";

    public ContainerToNetworkAffinityHostFilter(ServiceHost host, ContainerDescription desc) {
        this.host = host;
        this.networks = desc.networks;
        this.networkMode = desc.networkMode;
    }

    @Override
    public void filter(
            PlacementHostSelectionTaskService.PlacementHostSelectionTaskState state,
            Map<String, HostSelection> hostSelectionMap,
            HostSelectionFilterCompletion callback) {
        // sort the map in order to return consistent result no matter the order
        Map<String, HostSelection> sortedHostSelectionMap;
        if (hostSelectionMap != null) {
            sortedHostSelectionMap = new TreeMap<>(hostSelectionMap);
            if (sortedHostSelectionMap.isEmpty()) {
                callback.complete(sortedHostSelectionMap, null);
                return;
            }
        } else {
            sortedHostSelectionMap = null;
        }

        if (networkMode != null) {
            filterByNetworkMode(sortedHostSelectionMap);
        }

        if (networks != null && networks.size() > 0) {
            if (VolumeUtil.isContainerRequest(state.customProperties)) {
                findNetworkDescriptionsByLinks(state, sortedHostSelectionMap, callback, null);
            } else {
                findNetworkDescriptionsByComponent(state, sortedHostSelectionMap, callback);
            }
        } else {
            callback.complete(sortedHostSelectionMap, null);
        }
    }

    private void findNetworkDescriptionsByLinks(final PlacementHostSelectionTaskState state,
            final Map<String, HostSelection> hostSelectionMap,
            final HostSelectionFilterCompletion callback,
            List<String> containerNetworkLinks) {

        final QueryTask q = QueryUtil.buildQuery(ContainerNetworkDescription.class, false);

        QueryUtil.addCaseInsensitiveListValueClause(q, ContainerNetworkDescription.FIELD_NAME_NAME,
                networks.keySet());
        if (containerNetworkLinks != null) {
            QueryUtil.addListValueClause(q,
                    ContainerNetworkDescription.FIELD_NAME_SELF_LINK,
                    containerNetworkLinks);
        } else {
            QueryTask.Query contextClause = new QueryTask.Query()
                    .setTermPropertyName(QuerySpecification.buildCompositeFieldName(
                            ResourceState.FIELD_NAME_CUSTOM_PROPERTIES,
                            RequestUtils.FIELD_NAME_CONTEXT_ID_KEY))
                    .setTermMatchValue(state.contextId);
            q.querySpec.query.addBooleanClause(contextClause);
        }
        QueryUtil.addExpandOption(q);

        final Map<String, DescName> descLinksWithNames = new HashMap<>();
        new ServiceDocumentQuery<>(host, ContainerNetworkDescription.class)
                .query(q, (r) -> {
                    if (r.hasException()) {
                        host.log(Level.WARNING,
                                "Exception while filtering network descriptions. Error: [%s]",
                                r.getException().getMessage());
                        callback.complete(null, r.getException());
                    } else if (r.hasResult()) {
                        final ContainerNetworkDescription desc = r.getResult();
                        if (networks.keySet().contains(desc.name)) {
                            final DescName descName = new DescName();
                            descName.descLink = desc.documentSelfLink;
                            descName.descriptionName = desc.name;
                            descLinksWithNames.put(descName.descLink, descName);
                        }
                    } else {
                        if (descLinksWithNames.isEmpty()) {
                            callback.complete(hostSelectionMap, null);
                        } else {
                            findContainerNetworks(state, hostSelectionMap, descLinksWithNames,
                                    callback);
                        }
                    }
                });
    }

    private void findNetworkDescriptionsByComponent(final PlacementHostSelectionTaskState state,
            final Map<String, HostSelection> hostSelectionMap,
            final HostSelectionFilterCompletion callback) {
        String compositeComponentLink = UriUtils
                .buildUriPath(CompositeComponentFactoryService.SELF_LINK, state.contextId);
        host.sendRequest(Operation.createGet(UriUtils.buildUri(host, compositeComponentLink))
                .setReferer(host.getUri())
                .setCompletion((o, ex) -> {
                    if (o.getStatusCode() == Operation.STATUS_CODE_NOT_FOUND) {
                        // no composite componet found, just continue without fail
                        host.log(Level.FINE,
                                "Exception while getting CompositeComponent. Error: [%s]",
                                ex.getMessage());
                        callback.complete(hostSelectionMap, null);
                        return;
                    }
                    if (ex != null) {
                        host.log(Level.WARNING,
                                "Exception while getting CompositeComponent. Error: [%s]",
                                ex.getMessage());
                        callback.complete(null, ex);
                        return;
                    }
                    CompositeComponent body = o.getBody(CompositeComponent.class);
                    host.sendRequest(Operation
                            .createGet(UriUtils.buildUri(host, body.compositeDescriptionLink))
                            .setReferer(host.getUri())
                            .setCompletion((o2, ex2) -> {
                                if (ex2 != null) {
                                    host.log(Level.WARNING,
                                            "Exception while getting CompositeDescription. Error: [%s]",
                                            ex2.getMessage());
                                    callback.complete(null, ex2);
                                    return;
                                }
                                List<String> containerNetworkLinks = new ArrayList<>();
                                CompositeDescription descBody = o2
                                        .getBody(CompositeDescription.class);
                                for (String descriptionLink : descBody.descriptionLinks) {
                                    if (descriptionLink.startsWith(
                                            ContainerNetworkDescriptionService.FACTORY_LINK)) {
                                        containerNetworkLinks.add(descriptionLink);
                                    }
                                }
                                if (containerNetworkLinks.isEmpty()) {
                                    callback.complete(hostSelectionMap, null);
                                    return;
                                }
                                findNetworkDescriptionsByLinks(state, hostSelectionMap, callback,
                                        containerNetworkLinks);
                            }));
                }));
    }

    private void findContainerNetworks(final PlacementHostSelectionTaskState state,
            final Map<String, HostSelection> hostSelectionMap,
            final Map<String, DescName> descLinksWithNames,
            final HostSelectionFilterCompletion callback) {

        QueryTask q = QueryUtil.buildQuery(ContainerNetworkState.class, false);

        String compositeComponentLinksItemField = QueryTask.QuerySpecification
                .buildCollectionItemName(
                        ContainerNetworkState.FIELD_NAME_COMPOSITE_COMPONENT_LINKS);
        List<String> cclValues = new ArrayList<>(
                Arrays.asList(UriUtils.buildUriPath(CompositeComponentFactoryService.SELF_LINK,
                        state.contextId)));
        QueryUtil.addListValueClause(q, compositeComponentLinksItemField, cclValues);

        QueryUtil.addExpandOption(q);

        QueryUtil.addListValueClause(q,
                ContainerNetworkState.FIELD_NAME_DESCRIPTION_LINK, descLinksWithNames.keySet());

        new ServiceDocumentQuery<>(host, ContainerNetworkState.class)
                .query(q,
                        (r) -> {
                            if (r.hasException()) {
                                host.log(
                                        Level.WARNING,
                                        "Exception while selecting container networks with contextId [%s]. Error: [%s]",
                                        state.contextId, r.getException().getMessage());
                                callback.complete(null, r.getException());
                            } else if (r.hasResult()) {
                                ContainerNetworkState networkState = r.getResult();
                                final DescName descName = descLinksWithNames
                                        .get(networkState.descriptionLink);
                                if (descName != null) {
                                    descName.addResourceNames(
                                            Collections.singletonList(networkState.name));
                                    for (HostSelection hs : hostSelectionMap.values()) {
                                        hs.addDesc(descName);
                                    }

                                    if (networkState.parentLinks == null
                                            || networkState.parentLinks.isEmpty()) {
                                        descLinksWithNames.remove(networkState.descriptionLink);
                                    }
                                }
                            } else {
                                prefilterByNetworkHostLocation(state, hostSelectionMap,
                                        descLinksWithNames, callback);
                            }
                        });
    }

    /*
     * Get the actual network name if it has been resolved for any host!
     */
    private String getNetworkName(final Map<String, HostSelection> hostSelectionMap,
            final Map<String, DescName> descLinksWithNames) {

        DescName descName = descLinksWithNames.values().toArray(new DescName[0])[0];

        for (HostSelection hs : hostSelectionMap.values()) {
            if (hs.descNames != null) {
                for (DescName dn : hs.descNames.values()) {
                    if (descName.descriptionName.equals(dn.descriptionName)
                            && (dn.resourceNames != null) && (!dn.resourceNames.isEmpty())) {
                        return dn.resourceNames.iterator().next();
                    }
                }
            }
        }

        return descName.descriptionName;
    }

    private void prefilterByNetworkHostLocation(
            final PlacementHostSelectionTaskState state,
            final Map<String, HostSelection> hostSelectionMap,
            final Map<String, DescName> descLinksWithNames,
            final HostSelectionFilterCompletion callback) {

        Map<String, HostSelection> filteredHosts = new TreeMap<>();

        if (descLinksWithNames == null || descLinksWithNames.isEmpty()) {
            filterByClusterStoreAffinity(hostSelectionMap, filteredHosts, callback,
                    state);
            return;
        }

        String networkName = getNetworkName(hostSelectionMap, descLinksWithNames);
        QueryTask networkStateQuery = QueryUtil.buildPropertyQuery(
                ContainerNetworkState.class,
                ContainerNetworkState.FIELD_NAME_NAME,
                networkName.toLowerCase());

        QueryUtil.addExpandOption(networkStateQuery);

        new ServiceDocumentQuery<>(host, ContainerNetworkState.class).query(
                networkStateQuery, (res) -> {
                    if (res.hasException()) {
                        host.log(Level.WARNING,
                                "Exception while quering for container network [%s]. Error: [%s]",
                                descLinksWithNames.keySet().toArray(new String[0])[0],
                                res.getException().getMessage());
                        callback.complete(null, res.getException());
                    } else if (res.hasResult()) {
                        if (networkName.equals(res.getResult().name)) {
                            List<String> parentLinks = res.getResult().parentLinks;
                            if (parentLinks != null) {
                                for (String parentLink : parentLinks) {
                                    filteredHosts.put(parentLink, hostSelectionMap.get(parentLink));
                                }
                            }
                        }
                    } else {
                        filterByClusterStoreAffinity(hostSelectionMap, filteredHosts, callback,
                                state);
                    }
                });
    }

    private void filterByNetworkMode(Map<String, HostSelection> hostSelectionMap) {
        Iterator<Entry<String, HostSelection>> it = hostSelectionMap.entrySet().iterator();
        while (it.hasNext()) {
            Entry<String, HostSelection> entry = it.next();
            if (entry.getValue() == null) {
                it.remove();
            } else {
                HostSelection hs = entry.getValue();
                if (hs.hostType != null && hs.hostType.equals(ContainerHostType.VCH) &&
                        networkMode != null && networkMode.matches("host|none")) {
                    host.log(Level.WARNING,
                            "Host [%s] dropped because the the network mode 'host' and 'none' "
                                    + "are not allowed on VCH host.'",
                            hs.name);
                    it.remove();
                }
            }
        }
    }

    /*
     * This filter considers all the hosts equal, distinguishable only by whether they have a KV
     * store configured or not. At some point we may want to distinguish also special hosts (e.g.
     * Docker Swarm hosts, VIC hosts, etc.) which, as a single host, may be better alternative to
     * clusters of regular hosts sharing the same KV store.
     */
    private void filterByClusterStoreAffinity(
            final Map<String, HostSelection> hostSelectionMap,
            final Map<String, HostSelection> filteredMap,
            final HostSelectionFilterCompletion callback,
            PlacementHostSelectionTaskState state) {

        Map<String, HostSelection> filteredHosts = null;

        if ((filteredMap == null) || (filteredMap.isEmpty())) {
            /*
             * No filtered hosts due external networks.
             */
            filteredHosts = hostSelectionMap;
        } else {
            /*
             * Filtered hosts due external networks, remove from them the ones that are not included
             * in the hostSelectionMap.
             */
            Iterator<Entry<String, HostSelection>> it = filteredMap.entrySet().iterator();
            while (it.hasNext()) {
                Entry<String, HostSelection> entry = it.next();
                if (entry.getValue() == null) {
                    it.remove();
                }
            }
            filteredHosts = filteredMap;
        }

        if (filteredHosts == null || filteredHosts.isEmpty()) {
            callback.complete(filteredHosts, null);
            return;
        }

        if (filteredHosts.size() == 1) {
            /*
             * No big choice here... 0 or only 1 host available.
             */
            try {
                HostSelection hostSelection = filteredHosts.values()
                        .toArray(new HostSelection[1])[0];
                if (hostSelection.clusterStore == null || hostSelection.clusterStore.isEmpty()) {
                    Map<String, HostSelection> selected = Collections
                            .unmodifiableMap(filteredHosts);

                    updateUserDefinedNetworksWithSelectedHost(state, hostSelection.hostLink,
                            () -> {
                                callback.complete(selected, null);
                            });
                } else {
                    callback.complete(filteredHosts, null);
                }
            } catch (Throwable e) {
                host.log(Level.WARNING, "Exception when completing callback. Error: [%s]",
                        e.getMessage());
                callback.complete(null, e);
            }
            return;
        }

        /*
         * Transform the hostSelectionMap into a map where the keys group sets of hosts that share
         * the same KV store. This includes a special group with the key "NONE" for hosts without a
         * KV store configured.
         */

        Map<String, List<Entry<String, HostSelection>>> hostSelectionByKVStoreMap = new HashMap<>();

        for (Entry<String, HostSelection> entry : filteredHosts.entrySet()) {
            HostSelection hostSelection = entry.getValue();

            String clusterStoreKey = hostSelection.clusterStore;
            if (clusterStoreKey == null || clusterStoreKey.isEmpty()) {
                clusterStoreKey = NO_KV_STORE;
            }

            List<Entry<String, HostSelection>> set = hostSelectionByKVStoreMap.get(clusterStoreKey);
            if (set == null) {
                set = new ArrayList<>();
                hostSelectionByKVStoreMap.put(clusterStoreKey, set);
            }

            set.add(entry);
        }

        // Separate the hosts without a KV store configured.
        List<Entry<String, HostSelection>> nones = hostSelectionByKVStoreMap.remove(NO_KV_STORE);

        Map<String, HostSelection> hostSelectedMap = new HashMap<>();

        if (hostSelectionByKVStoreMap.isEmpty()) {
            /*
             * No hosts with KV store configured were found -> Pick one single host from the list of
             * hosts without a KV store configured.
             */

            // TODO - Picking one host randomly, it could pick the best single node available, e.g.
            // more resources, less containers, etc.

            if ((nones != null) && !nones.isEmpty()) {
                int chosen = pickOnePerContext(state, nones.size());
                Entry<String, HostSelection> entry = nones.get(chosen);
                hostSelectedMap.put(entry.getKey(), entry.getValue());
            }
        } else {
            /*
             * One or more sets of hosts with the same KV store configured were selected -> Pick one
             * of the clusters.
             */

            // TODO - Picking one cluster randomly, it could pick the best cluster available, e.g.
            // more resources, more hosts, less containers, better containers/host ratio, etc.
            int chosen = pickOnePerContext(state, hostSelectionByKVStoreMap.size());
            List<Entry<String, HostSelection>> entries = hostSelectionByKVStoreMap
                    .get(hostSelectionByKVStoreMap.keySet().toArray(new String[] {})[chosen]);
            for (Entry<String, HostSelection> entry : entries) {
                hostSelectedMap.put(entry.getKey(), entry.getValue());
            }
        }

        // Complete the callback with the selected hosts...

        try {
            callback.complete(hostSelectedMap, null);
        } catch (Throwable e) {
            host.log(Level.WARNING, "Exception when completing callback. Error: [%s]",
                    e.getMessage());
            callback.complete(null, e);
        }
    }

    private void updateUserDefinedNetworksWithSelectedHost(PlacementHostSelectionTaskState state,
            String hostLink, Runnable completion) {
        String compositeComponentLink = UriUtils
                .buildUriPath(CompositeComponentFactoryService.SELF_LINK, state.contextId);
        host.sendRequest(Operation.createGet(UriUtils.buildUri(host, compositeComponentLink))
                .setReferer(host.getUri())
                .setCompletion((o, ex) -> {
                    if (o.getStatusCode() == Operation.STATUS_CODE_NOT_FOUND) {
                        // no composite componet found, just continue without fail
                        host.log(Level.FINE,
                                "Exception while getting CompositeComponent. Error: [%s]",
                                ex.getMessage());
                        completion.run();
                        return;
                    }
                    if (ex != null) {
                        host.log(Level.WARNING,
                                "Exception while getting CompositeComponent. Error: [%s]",
                                ex.getMessage());
                        completion.run();
                        return;
                    }
                    CompositeComponent body = o.getBody(CompositeComponent.class);
                    host.sendRequest(Operation
                            .createGet(UriUtils.buildUri(host, body.compositeDescriptionLink))
                            .setReferer(host.getUri())
                            .setCompletion((o2, ex2) -> {
                                if (ex2 != null) {
                                    host.log(Level.WARNING,
                                            "Exception while getting CompositeDescription. Error: [%s]",
                                            ex2.getMessage());
                                    completion.run();
                                    return;
                                }
                                List<String> containerNetworkLinks = new ArrayList<>();
                                CompositeDescription descBody = o2
                                        .getBody(CompositeDescription.class);
                                for (String descriptionLink : descBody.descriptionLinks) {
                                    if (descriptionLink.startsWith(
                                            ContainerNetworkDescriptionService.FACTORY_LINK)) {
                                        containerNetworkLinks.add(descriptionLink);
                                    }
                                }
                                if (containerNetworkLinks.isEmpty()) {
                                    completion.run();
                                    return;
                                }
                                findNetworkDescriptionsByLinks(state, containerNetworkLinks,
                                        hostLink, completion);
                            }));
                }));

    }

    private void findNetworkDescriptionsByLinks(PlacementHostSelectionTaskState state,
            List<String> containerNetworkLinks, String hostLink,
            Runnable completion) {
        final QueryTask q = QueryUtil.buildQuery(ContainerNetworkDescription.class, false);

        QueryUtil.addCaseInsensitiveListValueClause(q, ContainerNetworkDescription.FIELD_NAME_NAME,
                networks.keySet());
        QueryUtil.addListValueExcludeClause(q, ContainerNetworkDescription.FIELD_NAME_EXTERNAL,
                Collections.singletonList("true"));
        if (containerNetworkLinks != null) {
            QueryUtil.addListValueClause(q,
                    ContainerNetworkDescription.FIELD_NAME_SELF_LINK,
                    containerNetworkLinks);
        } else {
            QueryTask.Query contextClause = new QueryTask.Query()
                    .setTermPropertyName(QuerySpecification.buildCompositeFieldName(
                            ResourceState.FIELD_NAME_CUSTOM_PROPERTIES,
                            RequestUtils.FIELD_NAME_CONTEXT_ID_KEY))
                    .setTermMatchValue(state.contextId);
            q.querySpec.query.addBooleanClause(contextClause);
        }
        QueryUtil.addExpandOption(q);

        final List<String> descLinkNames = new ArrayList<>();
        new ServiceDocumentQuery<>(host, ContainerNetworkDescription.class)
                .query(q, (r) -> {
                    if (r.hasException()) {
                        host.log(Level.WARNING,
                                "Exception while filtering network descriptions. Error: [%s]",
                                r.getException().getMessage());
                        completion.run();
                        return;
                    } else if (r.hasResult()) {
                        final ContainerNetworkDescription desc = r.getResult();
                        if (networks.keySet().contains(desc.name)) {
                            descLinkNames.add(desc.documentSelfLink);
                        }
                    } else {
                        if (descLinkNames.isEmpty()) {
                            completion.run();
                            return;
                        } else {
                            findContainerNetworks(state, descLinkNames, hostLink, completion);
                        }
                    }
                });
    }

    private void findContainerNetworks(PlacementHostSelectionTaskState state,
            List<String> descLinkNames, String hostLink,
            Runnable completion) {
        QueryTask q = QueryUtil.buildQuery(ContainerNetworkState.class, false);

        String compositeComponentLinksItemField = QueryTask.QuerySpecification
                .buildCollectionItemName(
                        ContainerNetworkState.FIELD_NAME_COMPOSITE_COMPONENT_LINKS);
        List<String> cclValues = new ArrayList<>(
                Arrays.asList(UriUtils.buildUriPath(CompositeComponentFactoryService.SELF_LINK,
                        state.contextId)));
        QueryUtil.addListValueClause(q, compositeComponentLinksItemField, cclValues);

        QueryUtil.addExpandOption(q);

        QueryUtil.addListValueClause(q,
                ContainerNetworkState.FIELD_NAME_DESCRIPTION_LINK, descLinkNames);

        new ServiceDocumentQuery<>(host, ContainerNetworkState.class)
                .query(q,
                        (r) -> {
                            if (r.hasException()) {
                                host.log(
                                        Level.WARNING,
                                        "Exception while selecting container networks with contextId [%s]. Error: [%s]",
                                        state.contextId, r.getException().getMessage());
                                completion.run();
                                return;
                            } else if (r.hasResult()) {
                                ContainerNetworkState networkState = r.getResult();
                                if (networkState.parentLinks == null) {
                                    networkState.parentLinks = new ArrayList<>();
                                }
                                networkState.parentLinks.add(hostLink);
                                host.sendRequest(
                                        Operation.createPatch(host, networkState.documentSelfLink)
                                                .setBody(networkState)
                                                .setReferer(state.documentSelfLink)
                                                .setCompletion((o, e) -> {
                                                    if (e != null) {
                                                        host.log(Level.SEVERE,
                                                                "Exception while updating network [%s] with host link [%s]. Error: [%s]",
                                                                networkState.name, hostLink,
                                                                e.getMessage());
                                                    }
                                                    completion.run();
                                                }));
                            }
                        });

    }

    private int pickOnePerContext(PlacementHostSelectionTaskState state, int itemsAvailable) {
        return Math.abs(
                state.customProperties.get(RequestUtils.FIELD_NAME_CONTEXT_ID_KEY).hashCode()
                        % itemsAvailable);
    }

    @Override
    public boolean isActive() {
        return networks != null && networks.size() > 0 || networkMode != null;
    }

    @Override
    public Map<String, AffinityConstraint> getAffinityConstraints() {
        return isActive() ? networks.entrySet().stream().collect(
                Collectors.toMap(p -> p.getKey(), p -> new AffinityConstraint(p.getKey())))
                : Collections.emptyMap();
    }
}
