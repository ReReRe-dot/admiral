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

package com.vmware.admiral.adapter.kubernetes.service;

import java.net.URI;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Logger;

import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedKeyManager;

import com.vmware.admiral.adapter.kubernetes.service.AbstractKubernetesAdapterService.KubernetesContext;
import com.vmware.admiral.adapter.kubernetes.service.apiobject.Namespace;
import com.vmware.admiral.adapter.kubernetes.service.apiobject.NamespaceList;
import com.vmware.admiral.common.security.EncryptionUtils;
import com.vmware.admiral.common.util.CertificateUtil;
import com.vmware.admiral.common.util.DelegatingX509KeyManager;
import com.vmware.admiral.common.util.ServerX509TrustManager;
import com.vmware.admiral.common.util.ServiceClientFactory;
import com.vmware.admiral.common.util.ServiceUtils;
import com.vmware.admiral.compute.kubernetes.KubernetesHostConstants;
import com.vmware.xenon.common.Operation;
import com.vmware.xenon.common.Operation.CompletionHandler;
import com.vmware.xenon.common.Service;
import com.vmware.xenon.common.Service.Action;
import com.vmware.xenon.common.ServiceClient;
import com.vmware.xenon.common.ServiceHost;
import com.vmware.xenon.common.UriUtils;
import com.vmware.xenon.common.Utils;

public class KubernetesRemoteApiClient {
    /*
     * Kubernetes API doesn't support field selectors just yet
     * https://github.com/kubernetes/kubernetes/issues/1362
     */
    static final String apiPrefix = "/api/v1";
    private static final String pingPath = "/healthz";

    private static final Logger logger = Logger
            .getLogger(KubernetesRemoteApiClient.class.getName());

    private static final int SSL_TRUST_RETRIES_COUNT = Integer.getInteger(
            "com.vmware.admiral.adapter.ssltrust.delegate.retries", 5);
    private static final long SSL_TRUST_RETRIES_WAIT = Long.getLong(
            "com.vmware.admiral.adapter.ssltrust.delegate.retries.wait.millis", 500);
    private static final int REQUEST_TIMEOUT_SECONDS = 10;

    private final ServiceHost host;
    private final ServiceClient serviceClient;
    private final DelegatingX509KeyManager keyManager = new DelegatingX509KeyManager();
    private ServerX509TrustManager trustManager;

    private static KubernetesRemoteApiClient INSTANCE = null;

    protected KubernetesRemoteApiClient(ServiceHost host, final TrustManager trustManager) {
        this.host = host;
        this.serviceClient = ServiceClientFactory.createServiceClient(trustManager, keyManager);

        if (trustManager instanceof ServerX509TrustManager) {
            this.trustManager = (ServerX509TrustManager) trustManager;
        }
    }

    public static synchronized KubernetesRemoteApiClient create(
            ServiceHost host, final TrustManager trustManager) {
        if (INSTANCE == null) {
            INSTANCE = new KubernetesRemoteApiClient(host, trustManager);
        }
        return INSTANCE;
    }

    public void stop() {
        /*if (attachServiceClient != null) {
            attachServiceClient.stop();
        }*/
        if (this.serviceClient != null) {
            this.serviceClient.stop();
        }
        /*if (largeDataClient != null) {
            largeDataClient.stop();
        }*/

        INSTANCE = null;
    }

    public void handleMaintenance(Operation post) {
        /*if (attachServiceClient != null) {
            attachServiceClient.handleMaintenance(post);
        }*/
        if (serviceClient != null) {
            serviceClient.handleMaintenance(post);
        }
        /*if (largeDataClient != null) {
            largeDataClient.handleMaintenance(post);
        }*/
    }

    private void createOrUpdateTargetSsl(KubernetesContext context) {
        if (context.credentials == null) {
            return;
        }

        /*if (!isSecure(input.getDockerUri())) {
            return;
        }*/

        String clientKey = EncryptionUtils.decrypt(context.credentials.privateKey);
        String clientCert = context.credentials.publicKey;
        String alias = context.host.address.toLowerCase();

        if (clientKey != null && !clientKey.isEmpty()) {
            X509ExtendedKeyManager delegateKeyManager = (X509ExtendedKeyManager) CertificateUtil
                    .getKeyManagers(alias, clientKey, clientCert)[0];
            keyManager.putDelegate(alias, delegateKeyManager);
        }

        String sslTrust = context.SSLTrustCertificate;

        if (sslTrust != null && trustManager != null) {
            String trustAlias = context.SSLTrustAlias;

            trustManager.putDelegate(trustAlias, sslTrust);
        }
    }

    private void prepareRequest(Operation op) {
        op.setReferer(URI.create("/"));
        op.forceRemote();

        if (op.getExpirationMicrosUtc() == 0) {
            long timeout = TimeUnit.SECONDS.toMicros(REQUEST_TIMEOUT_SECONDS);
            op.setExpiration(ServiceUtils.getExpirationTimeFromNowInMicros(timeout));
        }
    }

    public void ping(KubernetesContext context, CompletionHandler completionHandler) {
        createOrUpdateTargetSsl(context);

        URI uri = UriUtils.buildUri(context.host.address + pingPath);
        Operation op = Operation
                .createGet(uri)
                .setCompletion(completionHandler);

        prepareRequest(op);
        op.setExpiration(ServiceUtils.getExpirationTimeFromNowInMicros(
                TimeUnit.SECONDS.toMicros(10)));
        serviceClient.send(op);
    }

    public void doInfo(KubernetesContext context, CompletionHandler completionHandler) {
        createOrUpdateTargetSsl(context);

        // TODO: should be changed
        URI uri = UriUtils.buildUri(ApiUtil.namespacePrefix(context) + "/pods");
        /*
        sendRequest(Service.Action.GET, uri, null, (op, ex) -> {
            if (ex != null) {
                completionHandler.handle(op, ex);
            } else {
                String body = op.getBody(String.class);
                System.out.println(body);
            }
        });
        */
        sendRequest(Action.GET, uri, null, completionHandler);
    }

    public void getNamespaces(KubernetesContext context, CompletionHandler completionHandler) {
        createOrUpdateTargetSsl(context);
        URI uri = UriUtils.buildUri(ApiUtil.apiPrefix(context) + "/namespaces");
        sendRequest(Action.GET, uri, null, completionHandler);
    }

    public void createNamespaceIfMissing(KubernetesContext context, Consumer<Throwable> consumer) {
        createOrUpdateTargetSsl(context);

        URI uri = UriUtils.buildUri(ApiUtil.apiPrefix(context) + "/namespaces");
        String target = context.host.customProperties.get(KubernetesHostConstants
                .KUBERNETES_HOST_NAMESPACE_PROP_NAME);

        //getNamespaces(context, (operation, throwable) -> {
        sendRequest(Action.GET, uri, null, (operation, throwable) -> {
            if (throwable != null) {
                consumer.accept(throwable);
                return;
            }
            NamespaceList namespaceList = operation.getBody(NamespaceList.class);
            if (namespaceList == null) {
                consumer.accept(new IllegalStateException("Null body"));
                return;
            }
            for (Namespace namespace : namespaceList.items) {
                // Probably can skip null checks
                if (namespace.metadata != null &&
                        namespace.metadata.name != null &&
                        namespace.metadata.name.equals(target)) {
                    consumer.accept(null);
                    return;
                }
            }
            Namespace namespace = new Namespace();
            namespace.metadata.name = target;
            sendRequest(Action.POST, uri, Utils.toJson(namespace), (op, ex) -> consumer.accept(ex));
        });
    }

    public void getPods(KubernetesContext context, CompletionHandler completionHandler) {
        createOrUpdateTargetSsl(context);

        URI uri = UriUtils.buildUri(ApiUtil.namespacePrefix(context) + "/pods");
        sendRequest(Action.GET, uri, null, completionHandler);
    }

    private void sendRequest(Service.Action action, URI uri, Object body,
                             CompletionHandler completionHandler) {
        Operation op = Operation.createGet(uri)
                .setAction(action)
                .setBody(body)
                .setCompletion(completionHandler);

        prepareRequest(op);
        serviceClient.send(op);
    }
}
